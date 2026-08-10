package com.zhiyu.health.service.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.knowledge.KnowledgeChunk;
import com.zhiyu.health.mapper.knowledge.KnowledgeChunkMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.Neo4jException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 图谱在线管理写 seam（票 89 / grilling 决策 1、2）：B 端 admin 经本 service 直连 Neo4j
 * 编辑医学图谱，不经 server-py；读投影（G6 可视化）仍走 KnowledgeGraphController 转调 server-py。
 *
 * 白名单（contracts/graph-management.json 单一事实源）：仅 Symptom/Disease/Department 三类
 * 节点与 INDICATES/TREATED_BY/SUGGESTS_DEPARTMENT 三类关系开放在线编辑；Medication 是 PG
 * medications 表的快照投影、Contraindication 直接驱动用药禁忌红线规则，两者及药品关系继续走
 * "改 PG + 重放 seed" 离线链路，越白名单一律 400。
 *
 * 管理操作低频，一律自动提交单语句，不做跨语句事务：重名靠 "service 预检 + Neo4j 唯一约束
 * 兜底"（决策 6），预检与写入间的并发窗口由约束冲突翻译为友好 409 兜底。
 *
 * RAG 对齐只做运行时护栏（决策 3）：改名/删除 Symptom 时查 PG knowledge_chunks 同名 title，
 * 命中在响应带 ragChunkCount 警告，绝不 Neo4j+PG 联动双写；真正的对齐由后续 RAG 管理票解决。
 */
@Service
@RequiredArgsConstructor
public class GraphAdminService {

    private static final Logger log = LoggerFactory.getLogger(GraphAdminService.class);

    /** Neo4j 唯一约束冲突错误码：symptom_name_unique 等约束在 seed.cypher 初始化。 */
    private static final String CONSTRAINT_VIOLATION_CODE = "Neo.ClientError.Schema.ConstraintValidationFailed";

    /** knowledge_chunks.title 只与症状名同源（seed.cypher 头注），RAG 护栏仅对 Symptom 生效。 */
    private static final String SYMPTOM_LABEL = "Symptom";

    private static final int MAX_PAGE_SIZE = 200;

    private final Driver driver;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final Contracts contracts;

    // ---------- 节点查询 ----------

    public GraphPage<GraphNodeView> listNodes(String label, String keyword, int page, int size) {
        List<String> labels = resolveLabels(label);
        checkPage(page, size);
        String where = "WHERE any(l IN labels(n) WHERE l IN $labels) AND ($keyword = '' OR n.name CONTAINS $keyword) ";
        Map<String, Object> params = Map.of(
                "labels",
                labels,
                "keyword",
                keyword == null ? "" : keyword.trim(),
                "skip",
                (long) (page - 1) * size,
                "limit",
                (long) size);
        try (Session session = readSession()) {
            long total = singleLong(session, "MATCH (n) " + where + "RETURN count(n) AS c", params, "c");
            List<Record> records = session.run(
                            "MATCH (n) " + where + "RETURN n.node_id AS nodeId, "
                                    + "[l IN labels(n) WHERE l IN $labels][0] AS label, "
                                    + "n.name AS name, n.aliases AS aliases, n.description AS description "
                                    + "ORDER BY n.name SKIP $skip LIMIT $limit",
                            params)
                    .list();
            return new GraphPage<>(total, records.stream().map(this::toNodeView).toList());
        }
    }

    // ---------- 节点写 ----------

    public GraphNodeResult createNode(String label, GraphNodeProps props) {
        String canonicalLabel = requireLabel(label);
        String name = requireName(props.name());
        validatePropsSupport(canonicalLabel, props);
        String nodeId = nodeIdFor(canonicalLabel, name);
        Map<String, Object> propsMap = propsMap(props);
        try (Session session = writeSession()) {
            ensureNameAbsent(session, canonicalLabel, name);
            Record record;
            try {
                record = find(
                        session,
                        "CREATE (n:" + canonicalLabel + " {name: $name, node_id: $nodeId}) SET n += $props "
                                + "RETURN n.node_id AS nodeId, '" + canonicalLabel + "' AS label, "
                                + "n.name AS name, n.aliases AS aliases, n.description AS description",
                        Map.of("name", name, "nodeId", nodeId, "props", propsMap));
            } catch (Neo4jException e) {
                throw translateConstraintConflict(e);
            }
            // 操作人由入口 AuditFilter 统一记录；此处只记动作/label/node_id/属性键（硬约束 5 脱敏摘要）。
            log.info(
                    "graph-admin op=createNode label={} nodeId={} propKeys={}",
                    canonicalLabel,
                    nodeId,
                    propsMap.keySet());
            return new GraphNodeResult(toNodeView(record), null);
        }
    }

    public GraphNodeResult updateNode(String nodeId, GraphNodeProps props) {
        String label = labelOfNodeId(nodeId);
        validatePropsSupport(label, props);
        String newName = props.name() == null ? null : props.name().trim();
        if (newName != null && newName.isEmpty()) {
            throw new ApiException(400, "name 不能为空");
        }
        try (Session session = writeSession()) {
            Record existing = find(
                    session,
                    "MATCH (n:" + label + " {node_id: $nodeId}) RETURN n.name AS name",
                    Map.of("nodeId", nodeId));
            if (existing == null) {
                throw new ApiException(404, "节点不存在：" + nodeId);
            }
            String oldName = existing.get("name").asString();
            // name 是自然键：改名即 node_id 级联变更（决策 6），新 node_id 随响应返回给前端刷新引用。
            boolean renamed = newName != null && !newName.equals(oldName);
            if (renamed) {
                ensureNameAbsent(session, label, newName);
            }
            Map<String, Object> propsMap = propsMap(props);
            if (renamed) {
                propsMap.put("name", newName);
                propsMap.put("node_id", nodeIdFor(label, newName));
            }
            Record updated;
            try {
                updated = find(
                        session,
                        "MATCH (n:" + label + " {node_id: $nodeId}) SET n += $props "
                                + "RETURN n.node_id AS nodeId, '" + label + "' AS label, "
                                + "n.name AS name, n.aliases AS aliases, n.description AS description",
                        Map.of("nodeId", nodeId, "props", propsMap));
            } catch (Neo4jException e) {
                throw translateConstraintConflict(e);
            }
            // 护栏只盯改名：改名后旧 title 的 chunk 即漂移；未改名不存在漂移，不误报。
            Long ragWarning = renamed ? ragChunkCount(label, oldName) : null;
            log.info(
                    "graph-admin op=updateNode label={} nodeId={} newNodeId={} propKeys={}",
                    label,
                    nodeId,
                    updated.get("nodeId").asString(),
                    propsMap.keySet());
            return new GraphNodeResult(toNodeView(updated), ragWarning);
        }
    }

    /** 删除节点；返回 RAG 护栏计数（Symptom 命中同名 chunk 时非 null），供响应携带警告。 */
    public Long deleteNode(String nodeId) {
        String label = labelOfNodeId(nodeId);
        try (Session session = writeSession()) {
            Record found = find(
                    session,
                    "MATCH (n:" + label + " {node_id: $nodeId}) OPTIONAL MATCH (n)-[r]-() "
                            + "RETURN n.name AS name, count(r) AS rels",
                    Map.of("nodeId", nodeId));
            if (found == null) {
                throw new ApiException(404, "节点不存在：" + nodeId);
            }
            long rels = found.get("rels").asLong();
            // 删除保护（决策 5）：仍带关系的节点拒绝删除并回报关系计数，前端提示先逐条删关系；
            // 不开放 DETACH DELETE，避免误操作连带删边。
            if (rels > 0) {
                throw new ApiException(409, "节点仍关联 " + rels + " 条关系，请先删除全部关系");
            }
            session.run("MATCH (n:" + label + " {node_id: $nodeId}) DELETE n", Map.of("nodeId", nodeId))
                    .consume();
            Long ragWarning = ragChunkCount(label, found.get("name").asString());
            log.info("graph-admin op=deleteNode label={} nodeId={}", label, nodeId);
            return ragWarning;
        }
    }

    // ---------- 关系 ----------

    public GraphPage<GraphEdgeView> listEdges(String nodeId, String type, int page, int size) {
        // 类型缺省为白名单全集（排序固定顺序，保证分页 SKIP/LIMIT 跨页稳定）
        List<String> types = type == null || type.isBlank()
                ? contracts.graphManagement().edgeTypes().keySet().stream()
                        .sorted()
                        .toList()
                : List.of(requireEdgeType(type));
        checkPage(page, size);
        String where = "WHERE type(r) IN $types AND ($nodeId = '' OR a.node_id = $nodeId OR b.node_id = $nodeId) ";
        Map<String, Object> params = Map.of(
                "types",
                types,
                "nodeId",
                nodeId == null ? "" : nodeId,
                "skip",
                (long) (page - 1) * size,
                "limit",
                (long) size);
        try (Session session = readSession()) {
            long total = singleLong(session, "MATCH (a)-[r]->(b) " + where + "RETURN count(r) AS c", params, "c");
            List<Record> records = session.run(
                            "MATCH (a)-[r]->(b) " + where + "RETURN a.node_id AS fromNodeId, a.name AS fromName, "
                                    + "type(r) AS type, b.node_id AS toNodeId, b.name AS toName "
                                    + "ORDER BY fromNodeId, toNodeId SKIP $skip LIMIT $limit",
                            params)
                    .list();
            return new GraphPage<>(total, records.stream().map(this::toEdgeView).toList());
        }
    }

    public GraphEdgeView createEdge(String fromNodeId, String toNodeId, String type) {
        String edgeType = requireEdgeType(type);
        String fromLabel = labelOfNodeId(fromNodeId);
        String toLabel = labelOfNodeId(toNodeId);
        validateEdgeEndpoints(edgeType, fromLabel, toLabel);
        Map<String, Object> params = Map.of("from", fromNodeId, "to", toNodeId);
        String match = "MATCH (a:" + fromLabel + " {node_id: $from}), (b:" + toLabel + " {node_id: $to}) ";
        try (Session session = writeSession()) {
            long endpoints = singleLong(session, match + "RETURN count(*) AS c", params, "c");
            if (endpoints == 0) {
                throw new ApiException(404, "关系端点节点不存在");
            }
            // MERGE 幂等（与 seed.cypher 同风格）：重复创建同一关系不产生重边。
            Record record = find(
                    session,
                    match + "MERGE (a)-[r:" + edgeType + "]->(b) "
                            + "RETURN a.node_id AS fromNodeId, a.name AS fromName, "
                            + "type(r) AS type, b.node_id AS toNodeId, b.name AS toName",
                    params);
            log.info("graph-admin op=createEdge type={} from={} to={}", edgeType, fromNodeId, toNodeId);
            return toEdgeView(record);
        }
    }

    public void deleteEdge(String fromNodeId, String toNodeId, String type) {
        String edgeType = requireEdgeType(type);
        String fromLabel = labelOfNodeId(fromNodeId);
        String toLabel = labelOfNodeId(toNodeId);
        validateEdgeEndpoints(edgeType, fromLabel, toLabel);
        Map<String, Object> params = Map.of("from", fromNodeId, "to", toNodeId);
        String match = "MATCH (a:" + fromLabel + " {node_id: $from})-[r:" + edgeType + "]->(b:" + toLabel
                + " {node_id: $to}) ";
        try (Session session = writeSession()) {
            long count = singleLong(session, match + "RETURN count(r) AS c", params, "c");
            if (count == 0) {
                throw new ApiException(404, "关系不存在");
            }
            session.run(match + "DELETE r", params).consume();
            log.info("graph-admin op=deleteEdge type={} from={} to={}", edgeType, fromNodeId, toNodeId);
        }
    }

    // ---------- 白名单与校验 ----------

    private Contracts.GraphManagement whitelist() {
        return contracts.graphManagement();
    }

    /** label 白名单（决策 1）：大小写归一后必须命中契约 node_labels，返回契约原值；越名单 400。 */
    private String requireLabel(String label) {
        if (label == null) {
            throw new ApiException(400, "label 不能为空");
        }
        return whitelist().nodeLabels().stream()
                .filter(known -> known.equalsIgnoreCase(label.trim()))
                .findFirst()
                .orElseThrow(() -> new ApiException(400, "不支持的节点类型：" + label));
    }

    private List<String> resolveLabels(String label) {
        return label == null || label.isBlank() ? whitelist().nodeLabels() : List.of(requireLabel(label));
    }

    /** node_id 形如 {label}:{name}（ADR-0013 决策 6）：前缀即 label，同样受白名单约束。 */
    private String labelOfNodeId(String nodeId) {
        int idx = nodeId == null ? -1 : nodeId.indexOf(':');
        if (idx <= 0 || idx == nodeId.length() - 1) {
            throw new ApiException(400, "node_id 格式不合法，期望 {label}:{name}");
        }
        return requireLabel(nodeId.substring(0, idx));
    }

    private String requireEdgeType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!whitelist().edgeTypes().containsKey(normalized)) {
            throw new ApiException(400, "不支持的关系类型：" + type);
        }
        return normalized;
    }

    /** 关系语义白名单（决策 1）：类型与两端 label 组合必须匹配，如 INDICATES 仅 Symptom→Disease。 */
    private void validateEdgeEndpoints(String edgeType, String fromLabel, String toLabel) {
        Contracts.GraphManagement.EdgeEndpoints endpoints =
                whitelist().edgeTypes().get(edgeType);
        if (!endpoints.fromLabel().equals(fromLabel) || !endpoints.toLabel().equals(toLabel)) {
            throw new ApiException(400, edgeType + " 关系仅支持 " + endpoints.fromLabel() + "→" + endpoints.toLabel());
        }
    }

    /** 属性白名单：各 label 可编辑属性由契约 editable_properties 限定，越界 400 而非静默忽略。 */
    private void validatePropsSupport(String label, GraphNodeProps props) {
        List<String> editable = whitelist().editableProperties().getOrDefault(label, List.of());
        if (props.aliases() != null && !editable.contains("aliases")) {
            throw new ApiException(400, label + " 节点不支持 aliases 属性");
        }
        if (props.description() != null && !editable.contains("description")) {
            throw new ApiException(400, label + " 节点不支持 description 属性");
        }
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(400, "name 不能为空");
        }
        return name.trim();
    }

    private void checkPage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(400, "分页参数不合法");
        }
    }

    // ---------- Cypher 工具 ----------

    /** node_id 服务端生成（决策 6）：{label 小写}:{name}，客户端不可指定。 */
    private String nodeIdFor(String label, String name) {
        return label.toLowerCase(Locale.ROOT) + ":" + name;
    }

    /** 仅收集显式提供的属性；name/node_id 不入 props（改名时由调用方单独处理）。 */
    private Map<String, Object> propsMap(GraphNodeProps props) {
        Map<String, Object> map = new HashMap<>();
        if (props.aliases() != null) {
            map.put("aliases", props.aliases());
        }
        if (props.description() != null) {
            map.put("description", props.description());
        }
        return map;
    }

    /** 重名预检（决策 6）：name 有 Neo4j 唯一约束，此处提前给出友好 409。 */
    private void ensureNameAbsent(Session session, String label, String name) {
        long existing = singleLong(
                session, "MATCH (n:" + label + " {name: $name}) RETURN count(n) AS c", Map.of("name", name), "c");
        if (existing > 0) {
            throw new ApiException(409, "同名节点已存在：" + name);
        }
    }

    /** 预检与写入间的并发窗口由唯一约束兜底（决策 6）：约束冲突翻译为友好 409，其余异常原样上抛。 */
    private ApiException translateConstraintConflict(Neo4jException e) {
        if (CONSTRAINT_VIOLATION_CODE.equals(e.code())) {
            return new ApiException(409, "同名节点已存在");
        }
        return new ApiException(500, "图谱写入失败");
    }

    /**
     * RAG 对齐护栏（决策 3）：knowledge_chunks.title 与症状名同源，改名/删除 Symptom 后旧
     * title 的 chunk 即漂移。只统计并在响应带警告，由 B 端提示人工同步，绝不联动双写。
     */
    private Long ragChunkCount(String label, String symptomName) {
        if (!SYMPTOM_LABEL.equals(label)) {
            return null;
        }
        Long count = knowledgeChunkMapper.selectCount(
                Wrappers.<KnowledgeChunk>lambdaQuery().eq(KnowledgeChunk::getTitle, symptomName));
        return count != null && count > 0 ? count : null;
    }

    private Session readSession() {
        return driver.session(
                SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build());
    }

    private Session writeSession() {
        return driver.session(
                SessionConfig.builder().withDefaultAccessMode(AccessMode.WRITE).build());
    }

    private Record find(Session session, String cypher, Map<String, Object> params) {
        List<Record> records = session.run(cypher, params).list();
        return records.isEmpty() ? null : records.get(0);
    }

    private long singleLong(Session session, String cypher, Map<String, Object> params, String column) {
        Record record = find(session, cypher, params);
        return record == null ? 0 : record.get(column).asLong();
    }

    private GraphNodeView toNodeView(Record record) {
        Value aliases = record.get("aliases");
        Value description = record.get("description");
        return new GraphNodeView(
                record.get("nodeId").asString(),
                record.get("label").asString(),
                record.get("name").asString(),
                aliases.isNull() ? List.of() : aliases.asList(Value::asString),
                description.isNull() ? null : description.asString());
    }

    private GraphEdgeView toEdgeView(Record record) {
        return new GraphEdgeView(
                record.get("fromNodeId").asString(),
                record.get("fromName").asString(),
                record.get("type").asString(),
                record.get("toNodeId").asString(),
                record.get("toName").asString());
    }
}

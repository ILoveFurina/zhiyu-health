package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.mapper.knowledge.KnowledgeChunkMapper;
import com.zhiyu.health.service.knowledge.GraphAdminService;
import com.zhiyu.health.service.knowledge.GraphEdgeView;
import com.zhiyu.health.service.knowledge.GraphNodeProps;
import com.zhiyu.health.service.knowledge.GraphNodeResult;
import com.zhiyu.health.service.knowledge.GraphNodeView;
import com.zhiyu.health.service.knowledge.GraphPage;
import com.zhiyu.health.support.TestContracts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.summary.ResultSummary;

/**
 * 图谱管理写 seam（票 89）：增删改正常路径、删除保护 409、白名单 400、重名 409、
 * RAG 护栏命中/未命中。Driver/Session 全 mock，按 service 内语句顺序依次返回结果。
 */
@ExtendWith(MockitoExtension.class)
class GraphAdminServiceTest {

    @Mock
    private Driver driver;

    @Mock
    private Session session;

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    private GraphAdminService service;

    @BeforeEach
    void setUp() {
        lenient().when(driver.session(any(SessionConfig.class))).thenReturn(session);
        service = new GraphAdminService(driver, knowledgeChunkMapper, TestContracts.instance());
    }

    // ---------- 测试桩工具 ----------

    /** 按 service 内语句顺序依次返回结果；结果必须在 when() 之前创建，避免嵌套桩。 */
    private void stubRuns(Result... results) {
        org.mockito.stubbing.OngoingStubbing<Result> stubbing = when(session.run(anyString(), anyMap()));
        for (Result result : results) {
            stubbing = stubbing.thenReturn(result);
        }
    }

    private Result resultOf(Record... records) {
        Result result = mock(Result.class);
        ResultSummary summary = mock(ResultSummary.class);
        lenient().when(result.list()).thenReturn(List.of(records));
        lenient().when(result.consume()).thenReturn(summary);
        return result;
    }

    private Record recordOf(Map<String, Object> values) {
        Record record = mock(Record.class);
        lenient().when(record.get(anyString())).thenAnswer(inv -> {
            Object value = values.get(inv.getArgument(0));
            if (value == null) {
                return Values.NULL;
            }
            return value instanceof org.neo4j.driver.Value v ? v : Values.value(value);
        });
        return record;
    }

    private Record countRecord(long count) {
        return recordOf(Map.of("c", count));
    }

    private Record nodeRecord(String nodeId, String label, String name, Object aliases, Object description) {
        return recordOf(Map.of(
                "nodeId", nodeId,
                "label", label,
                "name", name,
                "aliases", aliases == null ? Values.NULL : Values.value(aliases),
                "description", description == null ? Values.NULL : Values.value(description)));
    }

    private Record edgeRecord(String from, String fromName, String type, String to, String toName) {
        return recordOf(Map.of(
                "fromNodeId", from,
                "fromName", fromName,
                "type", type,
                "toNodeId", to,
                "toName", toName));
    }

    // ---------- 创建节点 ----------

    @Test
    void createNodeReturnsServerGeneratedNodeId() {
        // 预检 0 → CREATE 返回新节点；node_id 由服务端生成，创建不查 RAG 护栏
        stubRuns(resultOf(countRecord(0)), resultOf(nodeRecord("symptom:耳鸣", "Symptom", "耳鸣", List.of("耳朵响"), null)));

        GraphNodeResult result = service.createNode("Symptom", new GraphNodeProps("耳鸣", List.of("耳朵响"), null));

        assertThat(result.node().nodeId()).isEqualTo("symptom:耳鸣");
        assertThat(result.node().label()).isEqualTo("Symptom");
        assertThat(result.node().aliases()).containsExactly("耳朵响");
        assertThat(result.ragChunkCount()).isNull();
        verify(knowledgeChunkMapper, never()).selectCount(any());
    }

    @Test
    void createNodeRejectsLabelOutsideWhitelist() {
        // 决策 1：Medication 是 PG 快照投影，不在在线编辑范围
        assertThatThrownBy(() -> service.createNode("Medication", new GraphNodeProps("布洛芬", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("不支持的节点类型");
                });
    }

    @Test
    void createNodeRejectsDuplicateNameByPrecheck() {
        stubRuns(resultOf(countRecord(1)));

        assertThatThrownBy(() -> service.createNode("Symptom", new GraphNodeProps("胸闷气短", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("同名节点已存在");
                });
    }

    @Test
    void createNodeTranslatesConstraintViolationToFriendly409() {
        // 决策 6：预检与写入间的并发窗口由 Neo4j 唯一约束兜底
        Neo4jException conflict = mock(Neo4jException.class);
        when(conflict.code()).thenReturn("Neo.ClientError.Schema.ConstraintValidationFailed");
        Result precheck = resultOf(countRecord(0));
        when(session.run(anyString(), anyMap())).thenReturn(precheck).thenThrow(conflict);

        assertThatThrownBy(() -> service.createNode("Symptom", new GraphNodeProps("胸闷气短", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("同名节点已存在");
                });
    }

    @Test
    void createNodeRejectsUnsupportedProperty() {
        // 属性白名单：Department 没有 aliases，越界 400 而非静默忽略
        assertThatThrownBy(() -> service.createNode("Department", new GraphNodeProps("耳鼻喉科", List.of("ENT"), "描述")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("不支持 aliases");
                });
    }

    // ---------- 更新节点 ----------

    @Test
    void updateNodeRenameReturnsNewNodeIdAndRagWarning() {
        // 改名：旧名查存在 → 新名预检 0 → SET 返回新 node_id；Symptom 命中 3 条同名 chunk 带警告
        stubRuns(
                resultOf(recordOf(Map.of("name", "胸闷气短"))),
                resultOf(countRecord(0)),
                resultOf(nodeRecord("symptom:胸闷", "Symptom", "胸闷", null, null)));
        when(knowledgeChunkMapper.selectCount(any())).thenReturn(3L);

        GraphNodeResult result = service.updateNode("symptom:胸闷气短", new GraphNodeProps("胸闷", null, null));

        assertThat(result.node().nodeId()).isEqualTo("symptom:胸闷");
        assertThat(result.ragChunkCount()).isEqualTo(3L);
    }

    @Test
    void updateNodeWithoutRenameSkipsRagGuard() {
        // 未改名不存在漂移：不查 PG、不带警告
        stubRuns(
                resultOf(recordOf(Map.of("name", "胸闷气短"))),
                resultOf(nodeRecord("symptom:胸闷气短", "Symptom", "胸闷气短", List.of("胸闷"), null)));

        GraphNodeResult result = service.updateNode("symptom:胸闷气短", new GraphNodeProps(null, List.of("胸闷"), null));

        assertThat(result.node().nodeId()).isEqualTo("symptom:胸闷气短");
        assertThat(result.ragChunkCount()).isNull();
        verify(knowledgeChunkMapper, never()).selectCount(any());
    }

    @Test
    void updateNodeRenameWithoutChunkHitReturnsNullWarning() {
        stubRuns(
                resultOf(recordOf(Map.of("name", "旧症状"))),
                resultOf(countRecord(0)),
                resultOf(nodeRecord("symptom:新症状", "Symptom", "新症状", null, null)));
        when(knowledgeChunkMapper.selectCount(any())).thenReturn(0L);

        GraphNodeResult result = service.updateNode("symptom:旧症状", new GraphNodeProps("新症状", null, null));

        assertThat(result.ragChunkCount()).isNull();
    }

    @Test
    void updateNodeReturns404WhenMissing() {
        stubRuns(resultOf());

        assertThatThrownBy(() -> service.updateNode("symptom:不存在", new GraphNodeProps(null, null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void updateNodeRejectsDuplicateRename() {
        stubRuns(resultOf(recordOf(Map.of("name", "旧症状"))), resultOf(countRecord(1)));

        assertThatThrownBy(() -> service.updateNode("symptom:旧症状", new GraphNodeProps("胸闷气短", null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void updateNodeRejectsMalformedNodeId() {
        assertThatThrownBy(() -> service.updateNode("没有前缀", new GraphNodeProps(null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("node_id 格式不合法");
                });
    }

    // ---------- 删除节点 ----------

    @Test
    void deleteNodeRejectsNodeWithRelationships() {
        // 决策 5：仍带关系的节点拒绝删除并回报关系计数，不开放 DETACH DELETE
        stubRuns(resultOf(recordOf(Map.of("name", "胸闷气短", "rels", 5L))));

        assertThatThrownBy(() -> service.deleteNode("symptom:胸闷气短")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(409);
            assertThat(e.getMessage()).contains("5 条关系");
        });
    }

    @Test
    void deleteNodeReturnsRagWarningForSymptom() {
        // 无关系 → DELETE；Symptom 命中 2 条同名 chunk 返回警告计数
        stubRuns(resultOf(recordOf(Map.of("name", "胸闷气短", "rels", 0L))), resultOf());
        when(knowledgeChunkMapper.selectCount(any())).thenReturn(2L);

        Long ragWarning = service.deleteNode("symptom:胸闷气短");

        assertThat(ragWarning).isEqualTo(2L);
    }

    @Test
    void deleteNodeSkipsRagGuardForNonSymptom() {
        stubRuns(resultOf(recordOf(Map.of("name", "骨科", "rels", 0L))), resultOf());

        Long ragWarning = service.deleteNode("department:骨科");

        assertThat(ragWarning).isNull();
        verify(knowledgeChunkMapper, never()).selectCount(any());
    }

    @Test
    void deleteNodeReturns404WhenMissing() {
        stubRuns(resultOf());

        assertThatThrownBy(() -> service.deleteNode("symptom:不存在"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ---------- 关系 ----------

    @Test
    void createEdgeMergesAndReturnsView() {
        // 端点存在（count=1）→ MERGE 返回关系视图
        stubRuns(
                resultOf(countRecord(1)),
                resultOf(edgeRecord("symptom:胸闷气短", "胸闷气短", "INDICATES", "disease:冠心病", "冠心病")));

        GraphEdgeView edge = service.createEdge("symptom:胸闷气短", "disease:冠心病", "INDICATES");

        assertThat(edge.type()).isEqualTo("INDICATES");
        assertThat(edge.fromNodeId()).isEqualTo("symptom:胸闷气短");
        assertThat(edge.toNodeId()).isEqualTo("disease:冠心病");
    }

    @Test
    void createEdgeRejectsTypeOutsideWhitelist() {
        // 决策 1：TREATS 属药品关系，走离线链路
        assertThatThrownBy(() -> service.createEdge("symptom:胸闷气短", "disease:冠心病", "TREATS"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("不支持的关系类型");
                });
    }

    @Test
    void createEdgeRejectsMismatchedEndpointLabels() {
        // 决策 1：INDICATES 仅 Symptom→Disease，方向/组合不符 400
        assertThatThrownBy(() -> service.createEdge("disease:冠心病", "department:心血管内科", "INDICATES"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("仅支持 Symptom→Disease");
                });
    }

    @Test
    void createEdgeReturns404WhenEndpointMissing() {
        stubRuns(resultOf(countRecord(0)));

        assertThatThrownBy(() -> service.createEdge("symptom:不存在", "disease:冠心病", "INDICATES"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(404);
                    assertThat(e.getMessage()).contains("端点节点不存在");
                });
    }

    @Test
    void deleteEdgeDeletesExistingRelationship() {
        stubRuns(resultOf(countRecord(1)), resultOf());

        service.deleteEdge("symptom:胸闷气短", "disease:冠心病", "INDICATES");

        // 先计数定位再删除，共两条语句
        verify(session, org.mockito.Mockito.times(2)).run(anyString(), anyMap());
    }

    @Test
    void deleteEdgeReturns404WhenMissing() {
        stubRuns(resultOf(countRecord(0)));

        assertThatThrownBy(() -> service.deleteEdge("symptom:胸闷气短", "disease:冠心病", "INDICATES"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ---------- 列表查询 ----------

    @Test
    void listNodesReturnsPagedItems() {
        stubRuns(
                resultOf(countRecord(1)), resultOf(nodeRecord("symptom:胸闷气短", "Symptom", "胸闷气短", List.of("胸闷"), null)));

        GraphPage<GraphNodeView> page = service.listNodes("symptom", "胸闷", 1, 20);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).nodeId()).isEqualTo("symptom:胸闷气短");
    }

    @Test
    void listEdgesReturnsPagedItemsFilteredByNode() {
        stubRuns(
                resultOf(countRecord(1)),
                resultOf(edgeRecord("symptom:胸闷气短", "胸闷气短", "INDICATES", "disease:冠心病", "冠心病")));

        GraphPage<GraphEdgeView> page = service.listEdges("symptom:胸闷气短", null, 1, 20);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items().get(0).toName()).isEqualTo("冠心病");
    }

    @Test
    void listRejectsInvalidPageParams() {
        assertThatThrownBy(() -> service.listNodes(null, null, 0, 20))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
    }
}

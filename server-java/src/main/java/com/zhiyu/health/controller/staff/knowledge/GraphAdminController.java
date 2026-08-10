package com.zhiyu.health.controller.staff.knowledge;

import com.zhiyu.health.controller.staff.knowledge.mapping.GraphInputMapper;
import com.zhiyu.health.service.knowledge.GraphAdminService;
import com.zhiyu.health.service.knowledge.GraphEdgeView;
import com.zhiyu.health.service.knowledge.GraphNodeResult;
import com.zhiyu.health.service.knowledge.GraphNodeView;
import com.zhiyu.health.service.knowledge.GraphPage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图谱在线管理（票 91）：admin 对 Neo4j 图谱的写端点与管理列表查询，业务全在
 * GraphAdminService。仅 admin 可访问（AdminInterceptor 保护 /api/b/**）。
 * 读投影（G6 可视化）保持在 KnowledgeGraphController 转调 server-py，与本写链路分离。
 */
@RestController
@RequestMapping("/api/b/knowledge/graph")
@RequiredArgsConstructor
public class GraphAdminController {

    private final GraphAdminService graphAdminService;
    private final GraphInputMapper graphInputMapper;

    /** 节点创建输入：node_id 由服务端按 {label}:{name} 生成，客户端不可指定（grilling 决策 6）。 */
    public record GraphNodeCreateInput(
            @NotBlank String label,
            @NotBlank @Size(max = 100) String name,
            List<@Size(max = 100) String> aliases,
            @Size(max = 500) String description) {}

    /**
     * 节点更新输入：name 提供且不同于原名即改名（node_id 级联变更，新值随响应返回）；
     * 字段为 null 表示不改动，显式提供才覆盖（含空串/空列表）。
     */
    public record GraphNodeUpdateInput(
            @Size(max = 100) String name, List<@Size(max = 100) String> aliases, @Size(max = 500) String description) {}

    /** 关系创建输入：两端 node_id + 类型；类型与两端 label 组合由 service 按契约白名单校验。 */
    public record GraphEdgeInput(@NotBlank String fromNodeId, @NotBlank String toNodeId, @NotBlank String type) {}

    /** 删除节点响应：Symptom 命中 PG knowledge_chunks 同名 title 时带 ragChunkCount 警告（不阻断）。 */
    public record GraphNodeDeleteResponse(Long ragChunkCount) {}

    /** 节点列表：label 缺省查全部白名单类型；keyword 按 name 模糊匹配。 */
    @GetMapping("/nodes")
    public GraphPage<GraphNodeView> listNodes(
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return graphAdminService.listNodes(label, keyword, page, size);
    }

    @PostMapping("/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public GraphNodeResult createNode(@Validated @RequestBody GraphNodeCreateInput input) {
        return graphAdminService.createNode(input.label(), graphInputMapper.toProps(input));
    }

    @PutMapping("/nodes/{nodeId}")
    public GraphNodeResult updateNode(@PathVariable String nodeId, @Validated @RequestBody GraphNodeUpdateInput input) {
        return graphAdminService.updateNode(nodeId, graphInputMapper.toProps(input));
    }

    @DeleteMapping("/nodes/{nodeId}")
    public GraphNodeDeleteResponse deleteNode(@PathVariable String nodeId) {
        return new GraphNodeDeleteResponse(graphAdminService.deleteNode(nodeId));
    }

    /** 关系列表：node_id 过滤两端任一端点；type 过滤关系类型。 */
    @GetMapping("/edges")
    public GraphPage<GraphEdgeView> listEdges(
            @RequestParam(required = false, name = "node_id") String nodeId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return graphAdminService.listEdges(nodeId, type, page, size);
    }

    @PostMapping("/edges")
    @ResponseStatus(HttpStatus.CREATED)
    public GraphEdgeView createEdge(@Validated @RequestBody GraphEdgeInput input) {
        return graphAdminService.createEdge(input.fromNodeId(), input.toNodeId(), input.type());
    }

    /** 删除关系为显式独立操作（grilling 决策 5）：按两端 node_id + 类型定位。 */
    @DeleteMapping("/edges")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEdge(
            @RequestParam("from_node_id") String fromNodeId,
            @RequestParam("to_node_id") String toNodeId,
            @RequestParam String type) {
        graphAdminService.deleteEdge(fromNodeId, toNodeId, type);
    }
}

package com.zhiyu.health.controller.staff.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.agentclient.AgentClient.GraphProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识图谱只读入口（票 13 / ADR-0013）：B 端可视化页经此 controller 访问图谱投影。
 *
 * 仅 admin 角色可访问（AdminInterceptor 保护 /api/b/**）。controller 零业务逻辑，
 * 只做鉴权透传与转调 server-py 只读知识接口；admin 浏览器不得直连 server-py 或 Neo4j。
 */
@RestController
@RequestMapping("/api/b/knowledge")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final AgentClient agentClient;

    /** 返回全图最小拓扑骨架 {nodes, edges}（ADR-0013 决策 6）。 */
    @GetMapping("/graph")
    public GraphProjection graph() {
        return agentClient.fetchGraphProjection();
    }

    /** 点击节点取详情：返回节点类型与全部属性（grilling 决策 6：属性不塞进投影）。 */
    @GetMapping("/graph/node")
    public JsonNode nodeDetail(@RequestParam("node_id") String nodeId) {
        return agentClient.fetchGraphNodeDetail(nodeId);
    }
}

package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.staff.knowledge.GraphAdminController;
import com.zhiyu.health.controller.staff.knowledge.mapping.GraphInputMapperImpl;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.service.knowledge.GraphAdminService;
import com.zhiyu.health.service.knowledge.GraphEdgeView;
import com.zhiyu.health.service.knowledge.GraphNodeProps;
import com.zhiyu.health.service.knowledge.GraphNodeResult;
import com.zhiyu.health.service.knowledge.GraphNodeView;
import com.zhiyu.health.service.knowledge.GraphPage;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 图谱管理端点（票 91）：admin 主链路冒烟 + doctor 403 权限负向 + 400/409 出口形状。 */
@WebMvcTest(GraphAdminController.class)
@Import(GraphInputMapperImpl.class)
class GraphAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GraphAdminService graphAdminService;

    private static final String CREATE_NODE_BODY =
            """
            {"label": "Symptom", "name": "耳鸣", "aliases": ["耳朵响"]}
            """;

    private GraphNodeView demoNode() {
        return new GraphNodeView("symptom:耳鸣", "Symptom", "耳鸣", List.of("耳朵响"), null);
    }

    // ---------- 节点 ----------

    @Test
    void listNodesReturnsPage() throws Exception {
        when(graphAdminService.listNodes("Symptom", "耳鸣", 1, 20)).thenReturn(new GraphPage<>(1, List.of(demoNode())));

        mockMvc.perform(get("/api/b/knowledge/graph/nodes")
                        .param("label", "Symptom")
                        .param("keyword", "耳鸣")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].node_id").value("symptom:耳鸣"))
                .andExpect(jsonPath("$.items[0].label").value("Symptom"));
    }

    @Test
    void createNodeReturns201WithServerGeneratedNodeId() throws Exception {
        when(graphAdminService.createNode(eq("Symptom"), any(GraphNodeProps.class)))
                .thenReturn(new GraphNodeResult(demoNode(), null));

        mockMvc.perform(post("/api/b/knowledge/graph/nodes")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(CREATE_NODE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.node.node_id").value("symptom:耳鸣"))
                .andExpect(jsonPath("$.node.aliases[0]").value("耳朵响"))
                .andExpect(jsonPath("$.rag_chunk_count").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void createNodeRejectsDoctorRole() throws Exception {
        mockMvc.perform(post("/api/b/knowledge/graph/nodes")
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(CREATE_NODE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void createNodeRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/b/knowledge/graph/nodes")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"label\": \"Symptom\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createNodePropagatesWhitelist400() throws Exception {
        when(graphAdminService.createNode(eq("Medication"), any(GraphNodeProps.class)))
                .thenThrow(new ApiException(400, "不支持的节点类型：Medication"));

        mockMvc.perform(post("/api/b/knowledge/graph/nodes")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"label\": \"Medication\", \"name\": \"布洛芬\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("不支持的节点类型：Medication"));
    }

    @Test
    void updateNodeReturnsNewNodeIdAndRagWarning() throws Exception {
        when(graphAdminService.updateNode(eq("symptom:胸闷气短"), any(GraphNodeProps.class)))
                .thenReturn(new GraphNodeResult(new GraphNodeView("symptom:胸闷", "Symptom", "胸闷", List.of(), null), 3L));

        mockMvc.perform(put("/api/b/knowledge/graph/nodes/symptom:胸闷气短")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"name\": \"胸闷\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.node_id").value("symptom:胸闷"))
                .andExpect(jsonPath("$.rag_chunk_count").value(3));
    }

    @Test
    void updateNodePropagatesDuplicate409() throws Exception {
        when(graphAdminService.updateNode(eq("symptom:旧症状"), any(GraphNodeProps.class)))
                .thenThrow(new ApiException(409, "同名节点已存在：胸闷气短"));

        mockMvc.perform(put("/api/b/knowledge/graph/nodes/symptom:旧症状")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"name\": \"胸闷气短\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("同名节点已存在：胸闷气短"));
    }

    @Test
    void deleteNodeReturnsRagWarning() throws Exception {
        when(graphAdminService.deleteNode("symptom:胸闷气短")).thenReturn(2L);

        mockMvc.perform(delete("/api/b/knowledge/graph/nodes/symptom:胸闷气短")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rag_chunk_count").value(2));
    }

    @Test
    void deleteNodePropagatesProtection409() throws Exception {
        doThrow(new ApiException(409, "节点仍关联 5 条关系，请先删除全部关系"))
                .when(graphAdminService)
                .deleteNode("symptom:胸闷气短");

        mockMvc.perform(delete("/api/b/knowledge/graph/nodes/symptom:胸闷气短")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("节点仍关联 5 条关系，请先删除全部关系"));
    }

    @Test
    void deleteNodeRejectsDoctorRole() throws Exception {
        mockMvc.perform(delete("/api/b/knowledge/graph/nodes/symptom:胸闷气短")
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    // ---------- 关系 ----------

    @Test
    void listEdgesReturnsPage() throws Exception {
        when(graphAdminService.listEdges("symptom:胸闷气短", null, 1, 20))
                .thenReturn(new GraphPage<>(
                        1, List.of(new GraphEdgeView("symptom:胸闷气短", "胸闷气短", "INDICATES", "disease:冠心病", "冠心病"))));

        mockMvc.perform(get("/api/b/knowledge/graph/edges")
                        .param("node_id", "symptom:胸闷气短")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].type").value("INDICATES"))
                .andExpect(jsonPath("$.items[0].to_node_id").value("disease:冠心病"));
    }

    @Test
    void createEdgeReturns201() throws Exception {
        when(graphAdminService.createEdge("symptom:胸闷气短", "disease:冠心病", "INDICATES"))
                .thenReturn(new GraphEdgeView("symptom:胸闷气短", "胸闷气短", "INDICATES", "disease:冠心病", "冠心病"));

        mockMvc.perform(
                        post("/api/b/knowledge/graph/edges")
                                .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                                .contentType("application/json")
                                .content(
                                        "{\"from_node_id\": \"symptom:胸闷气短\", \"to_node_id\": \"disease:冠心病\", \"type\": \"INDICATES\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.from_node_id").value("symptom:胸闷气短"))
                .andExpect(jsonPath("$.type").value("INDICATES"));
    }

    @Test
    void createEdgeRejectsDoctorRole() throws Exception {
        mockMvc.perform(
                        post("/api/b/knowledge/graph/edges")
                                .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR))
                                .contentType("application/json")
                                .content(
                                        "{\"from_node_id\": \"symptom:胸闷气短\", \"to_node_id\": \"disease:冠心病\", \"type\": \"INDICATES\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void deleteEdgeReturns204() throws Exception {
        mockMvc.perform(delete("/api/b/knowledge/graph/edges")
                        .param("from_node_id", "symptom:胸闷气短")
                        .param("to_node_id", "disease:冠心病")
                        .param("type", "INDICATES")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNoContent());
    }
}

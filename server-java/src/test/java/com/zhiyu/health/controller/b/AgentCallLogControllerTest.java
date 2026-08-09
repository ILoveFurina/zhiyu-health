package com.zhiyu.health.controller.b;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.controller.staff.chat.AgentCallLogController;
import com.zhiyu.health.entity.chat.AgentCallLog;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.service.chat.AgentCallLogService;
import com.zhiyu.health.support.StaffTokens;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** B 端 Agent 调用日志：仅 admin 角色可见，无 token 401 / doctor 403 / 不存在会话返空列表。 */
@WebMvcTest(AgentCallLogController.class)
class AgentCallLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentCallLogService service;

    @Test
    void noTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/b/agent-call-logs/conversations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/b/agent-call-logs").param("conversation_id", "7"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doctorTokenIsForbidden() throws Exception {
        mockMvc.perform(get("/api/b/agent-call-logs/conversations")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/b/agent-call-logs")
                        .param("conversation_id", "7")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminListsConversationsWithTrace() throws Exception {
        when(service.listConversations(null))
                .thenReturn(List.of(new AgentCallLogService.ConversationView(
                        7L, 12L, "我头疼两天了", "小明", OffsetDateTime.parse("2026-08-03T10:00:00Z"))));
        mockMvc.perform(get("/api/b/agent-call-logs/conversations")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversation_id").value(7))
                .andExpect(jsonPath("$[0].conversation_title").value("我头疼两天了"))
                .andExpect(jsonPath("$[0].patient_id").value(12))
                .andExpect(jsonPath("$[0].patient_nickname").value("小明"));
    }

    @Test
    void adminFiltersConversationsByPatientNickname() throws Exception {
        when(service.listConversations("小明"))
                .thenReturn(List.of(new AgentCallLogService.ConversationView(
                        7L, 12L, "我头疼两天了", "小明", OffsetDateTime.parse("2026-08-03T10:00:00Z"))));
        mockMvc.perform(get("/api/b/agent-call-logs/conversations")
                        .param("patient", "小明")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patient_nickname").value("小明"));
    }

    @Test
    void adminQueriesEmptyConversationReturnsEmptyList() throws Exception {
        // 不存在的 conversation_id 返回空列表（不 404）
        when(service.listByConversation(999L)).thenReturn(List.of());
        mockMvc.perform(get("/api/b/agent-call-logs")
                        .param("conversation_id", "999")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void adminViewsCallChainOrderedByRoundAndSeq() throws Exception {
        AgentCallLog start = new AgentCallLog(
                34L, 7L, 12L, "call-1", "recommend_doctors", AgentCallLog.PHASE_TOOL_START, null, null, null, 1);
        start.setId(101L);
        AgentCallLog end = new AgentCallLog(
                34L, 7L, 12L, "call-1", "recommend_doctors", AgentCallLog.PHASE_TOOL_END, "success", 120, null, 2);
        end.setId(102L);
        when(service.listByConversation(7L)).thenReturn(List.of(start, end));

        mockMvc.perform(get("/api/b/agent-call-logs")
                        .param("conversation_id", "7")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tool_call_id").value("call-1"))
                .andExpect(jsonPath("$[0].phase").value("tool_start"))
                .andExpect(jsonPath("$[0].result").doesNotExist())
                .andExpect(jsonPath("$[1].phase").value("tool_end"))
                .andExpect(jsonPath("$[1].result").value("success"))
                .andExpect(jsonPath("$[1].duration_ms").value(120));
    }
}

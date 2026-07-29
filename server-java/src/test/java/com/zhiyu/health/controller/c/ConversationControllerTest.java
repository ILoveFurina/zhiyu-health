package com.zhiyu.health.controller.c;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.service.ConversationService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 会话消息持久化与对话记录 API 的 HTTP seam。 */
class ConversationControllerTest {

    @Test
    void messagesExposeDisclaimerOnlyForAiText() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        Message user = message(1L, "user", "text", "我头疼");
        Message assistant = message(2L, "assistant", "text", "建议挂神经内科");
        Message redFlag = message(3L, "assistant", "red_flag", "请拨打 120");
        when(conversations.listMessagesForPatient(7L, 12L))
                .thenReturn(List.of(view(user, null), view(assistant, "仅供参考，不替代医生诊断"), view(redFlag, null)));
        MockMvc mvc = standalone(conversations);

        mvc.perform(get("/api/c/conversations/7/messages").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].disclaimer").doesNotExist())
                .andExpect(jsonPath("$[1].disclaimer").value("仅供参考，不替代医生诊断"))
                .andExpect(jsonPath("$[2].disclaimer").doesNotExist());
    }

    @Test
    void listReturnsConversationsOrderedByLastActiveWithThreeFieldsOnly() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.listForPatient(12L))
                .thenReturn(List.of(
                        summary(31L, "头疼挂什么科", "2026-07-29T10:00:00+08:00"),
                        summary(7L, "新对话", "2026-07-28T09:00:00+08:00")));
        MockMvc mvc = standalone(conversations);

        mvc.perform(get("/api/c/conversations").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                // 最近活跃倒序
                .andExpect(jsonPath("$[0].id").value(31))
                .andExpect(jsonPath("$[0].title").value("头疼挂什么科"))
                .andExpect(jsonPath("$[0].last_active_at").value("2026-07-29T10:00:00+08:00"))
                .andExpect(jsonPath("$[1].id").value(7))
                .andExpect(jsonPath("$[1].last_active_at").value("2026-07-28T09:00:00+08:00"))
                // 严格三字段：不返预览（决策 12）
                .andExpect(jsonPath("$[0].preview").doesNotExist())
                .andExpect(jsonPath("$[0].content").doesNotExist());
        verify(conversations).listForPatient(12L);
    }

    @Test
    void listIsEmptyWhenPatientHasNoConversations() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.listForPatient(12L)).thenReturn(List.of());
        MockMvc mvc = standalone(conversations);

        mvc.perform(get("/api/c/conversations").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void deleteRemovesConversationAndYields204() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        MockMvc mvc = standalone(conversations);

        mvc.perform(delete("/api/c/conversations/7").requestAttr("authSubject", "12"))
                .andExpect(status().isNoContent());
        verify(conversations).deleteForPatient(7L, 12L);
    }

    @Test
    void deleteForeignOrMissingConversationReturns404() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        // 不区分“不是你的”与“不存在”，一律 404（决策 3）
        doThrow(new ApiException(404, "会话不存在")).when(conversations).deleteForPatient(7L, 12L);
        MockMvc mvc = standalone(conversations);

        mvc.perform(delete("/api/c/conversations/7").requestAttr("authSubject", "12"))
                .andExpect(status().isNotFound());
    }

    private MockMvc standalone(ConversationService conversations) {
        // 挂上统一 advice，覆盖 ApiException → 404 映射
        return MockMvcBuilders.standaloneSetup(new ConversationController(conversations))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Message message(Long id, String role, String kind, String content) {
        Message message = new Message(id, 7L, role, kind, content, null);
        message.setCreatedAt(OffsetDateTime.parse("2026-07-28T10:00:00+08:00"));
        return message;
    }

    private ConversationService.MessageView view(Message message, String disclaimer) {
        return new ConversationService.MessageView(
                message.getId(),
                message.getRole(),
                message.getKind(),
                message.getContent(),
                message.getEffort(),
                disclaimer,
                message.getCreatedAt().toString());
    }

    private ConversationService.ConversationSummary summary(Long id, String title, String lastActiveAt) {
        return new ConversationService.ConversationSummary(id, title, lastActiveAt);
    }
}

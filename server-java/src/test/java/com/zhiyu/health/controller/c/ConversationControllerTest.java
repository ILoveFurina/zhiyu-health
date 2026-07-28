package com.zhiyu.health.controller.c;

import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 会话消息持久化 API 的 HTTP seam。 */
class ConversationControllerTest {

    @Test
    void messagesExposeDisclaimerOnlyForAiText() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.getForPatient(7L, 12L)).thenReturn(new Conversation(7L, 12L, "头疼"));
        Message user = message(1L, "user", "text", "我头疼");
        Message assistant = message(2L, "assistant", "text", "建议挂神经内科");
        Message redFlag = message(3L, "assistant", "red_flag", "请拨打 120");
        when(conversations.listMessages(7L)).thenReturn(List.of(user, assistant, redFlag));
        MockMvc mvc = standaloneSetup(new ConversationController(conversations)).build();

        mvc.perform(get("/api/c/conversations/7/messages")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].disclaimer").doesNotExist())
                .andExpect(jsonPath("$[1].disclaimer").value("仅供参考，不替代医生诊断"))
                .andExpect(jsonPath("$[2].disclaimer").doesNotExist());
    }

    private Message message(Long id, String role, String kind, String content) {
        Message message = new Message(id, 7L, role, kind, content, null);
        message.setCreatedAt(OffsetDateTime.parse("2026-07-28T10:00:00+08:00"));
        return message;
    }
}

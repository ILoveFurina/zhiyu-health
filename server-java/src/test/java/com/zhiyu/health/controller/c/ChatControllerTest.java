package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.service.ChatRoundService;
import com.zhiyu.health.service.ChatService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 保留的 HTTP SSE 适配器：request_id 必填且只负责装配 ChatRoundService.Command。 */
class ChatControllerTest {

    @Test
    void requestIdIsRequired() throws Exception {
        MockMvc mvc = mvc(mock(ChatService.class));

        mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", 12L)
                        .contentType("application/json")
                        .content("{\"content\":\"你好\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validRequestStartsSseAndForwardsSameRequestId() throws Exception {
        ChatService service = mock(ChatService.class);
        SseEmitter emitter = new SseEmitter();
        when(service.chat(any())).thenReturn(emitter);
        MockMvc mvc = mvc(service);

        mvc.perform(
                        post("/api/c/chat")
                                .requestAttr("authSubject", 12L)
                                .contentType("application/json")
                                .content(
                                        """
                                {"request_id":"req-34","content":"你好","effort":"quick"}
                                """))
                .andExpect(request().asyncStarted());

        verify(service).chat(new ChatRoundService.Command(12L, "req-34", null, "你好", "quick", null, null, null, null));
        emitter.complete();
    }

    private MockMvc mvc(ChatService service) {
        ObjectMapper mapper = new ObjectMapper();
        return MockMvcBuilders.standaloneSetup(new ChatController(service))
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }
}

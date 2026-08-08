package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.controller.patient.chat.ChatController;
import com.zhiyu.health.service.chat.ChatRoundService;
import com.zhiyu.health.service.chat.ChatService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Sinks;

/** SSE 薄适配器出口：响应提交前失败走 HTTP 错误；提交后失败安静收尾，避免 No converter 二次噪音（票 33）。 */
class ChatServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void failureBeforeFirstEventPropagatesAsHttpError() throws Exception {
        Sinks.Many<ChatRoundService.Event> upstream = Sinks.many().replay().all();
        MockMvc mvc = mvc(upstream);
        MvcResult result = start(mvc);

        upstream.tryEmitError(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> mvc.perform(asyncDispatch(result))).hasRootCauseMessage("connection refused");
    }

    @Test
    void failureAfterEventsCompletesQuietlyWithoutConverterNoise() throws Exception {
        Sinks.Many<ChatRoundService.Event> upstream = Sinks.many().replay().all();
        MockMvc mvc = mvc(upstream);
        MvcResult result = start(mvc);

        upstream.tryEmitNext(
                new ChatRoundService.Event("meta", mapper.createObjectNode().put("conversation_id", 7)));
        upstream.tryEmitError(new RuntimeException("upstream boom"));

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // 已送达的 meta 保留；失败细节只进服务端日志，端侧拿到干净收尾而非异常噪音
        assertThat(body).contains("event:meta");
        assertThat(body).doesNotContain("upstream boom");
    }

    private MockMvc mvc(Sinks.Many<ChatRoundService.Event> upstream) {
        ChatRoundService rounds = mock(ChatRoundService.class);
        when(rounds.accept(any())).thenReturn(new ChatRoundService.Handle("req-1", 7L, "ACCEPTED", upstream.asFlux()));
        return MockMvcBuilders.standaloneSetup(new ChatController(new ChatService(rounds, mapper)))
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private MvcResult start(MockMvc mvc) throws Exception {
        return mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", 12L)
                        .contentType("application/json")
                        .content("{\"request_id\":\"req-1\",\"content\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
    }
}

package com.zhiyu.health.controller.c;

import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/** 对话链路骨架：server-py 的 SSE 事件原样透传，且 event 行先于 data 行（小程序端按行序解析） */
class ChatControllerTest {

    @Test
    void chatPassesThroughSseEventsWithNameBeforeData() throws Exception {
        AgentClient agentClient = mock(AgentClient.class);
        when(agentClient.chat(anyMap())).thenReturn(Flux.just(
                ServerSentEvent.builder("{\"effort\": \"low\"}").event("meta").build(),
                ServerSentEvent.builder("{\"text\": \"你好\"}").event("token").build(),
                ServerSentEvent.builder("{}").event("done").build()
        ));
        MockMvc mvc = standaloneSetup(new ChatController(new ChatService(agentClient)))
                // 对齐生产：Boot 自动配置的 StringHttpMessageConverter 默认 UTF-8，
                // standalone MockMvc 的默认转换器是 ISO-8859-1，需显式指定
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult result = mvc.perform(post("/api/c/chat")
                        .contentType("application/json")
                        .content("{\"content\": \"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:meta\ndata:{\"effort\": \"low\"}");
        assertThat(body).contains("event:token\ndata:{\"text\": \"你好\"}");
        assertThat(body).contains("event:done");
    }
}

package com.zhiyu.health.agentclient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;

/** 调 server-py（Agent 层）的 SSE 客户端 */
@Component
public class AgentClient {

    private final WebClient webClient;

    public AgentClient(WebClient.Builder builder,
                       @Value("${zhiyu.agent.base-url}") String baseUrl,
                       @Value("${zhiyu.agent.callback-secret}") String callbackSecret) {
        this.webClient = builder.baseUrl(baseUrl)
                .defaultHeader("X-Agent-Callback-Token", callbackSecret)
                .build();
    }

    /** 发起对话请求，返回 SSE 事件流 */
    public Flux<ServerSentEvent<String>> chat(Map<String, Object> requestBody) {
        return webClient.post()
                .uri("/api/agent/chat")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                });
    }
}

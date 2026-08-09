package com.zhiyu.health.agentclient;

import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/** server-py 对话与通用药品知识 SSE 能力。 */
final class ChatAgentApi {
    private final WebClient webClient;

    ChatAgentApi(WebClient webClient) {
        this.webClient = webClient;
    }

    Flux<ServerSentEvent<String>> chat(Map<String, Object> requestBody) {
        return webClient
                .post()
                .uri("/api/agent/chat")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    Flux<ServerSentEvent<String>> medicationKnowledge(String drugName) {
        return webClient
                .post()
                .uri("/api/agent/medication/knowledge")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("drug_name", drugName))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }
}

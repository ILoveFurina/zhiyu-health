package com.zhiyu.health.agentclient;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/** server-py 医学知识图谱只读查询能力；不可用时降级为空展示。 */
final class KnowledgeGraphAgentApi {
    private final WebClient webClient;

    KnowledgeGraphAgentApi(WebClient webClient) {
        this.webClient = webClient;
    }

    AgentClient.GraphProjection projection() {
        try {
            AgentClient.GraphProjection response = webClient
                    .get()
                    .uri("/api/knowledge/graph")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(AgentClient.GraphProjection.class)
                    .block(Duration.ofSeconds(15));
            return response == null ? new AgentClient.GraphProjection(List.of(), List.of()) : response;
        } catch (RuntimeException e) {
            return new AgentClient.GraphProjection(List.of(), List.of());
        }
    }

    JsonNode nodeDetail(String nodeId) {
        try {
            return webClient
                    .get()
                    .uri(builder -> builder.path("/api/knowledge/graph/node")
                            .queryParam("node_id", nodeId)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(15));
        } catch (RuntimeException e) {
            return null;
        }
    }
}

package com.zhiyu.health.agentclient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 知识文档在线 embedding 调用与错误契约（ADR-0036）。
 *
 * <p>server-java 切分文档后调 server-py {@code /api/agent/knowledge/embeddings} 批量计算向量，
 * 再由 server-java 写 knowledge_chunks。本类与 VisionAgentApi/VoiceAgentApi 同构：
 * 共享 WebClient（header 自动带回调密钥）、错误码白名单提取 {@code detail.code}、超时检测。
 */
final class KnowledgeEmbeddingAgentApi {
    private static final String MODEL_TIMEOUT = "EMBEDDING_MODEL_TIMEOUT";
    private static final String AGENT_UNAVAILABLE = "EMBEDDING_MODEL_FAILED";
    private static final String UNAVAILABLE_MESSAGE = "向量计算服务暂不可用";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Set<String> errorCodes;

    KnowledgeEmbeddingAgentApi(WebClient webClient, ObjectMapper objectMapper, Contracts contracts) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.errorCodes = Set.copyOf(contracts.knowledgeDocuments().embedding().errorCodes());
    }

    /**
     * 批量计算 embedding：输入 (title, content) 列表，返回向量列表。
     *
     * <p>拼接格式 {@code f"{title}。{content}"} 在 server-py 侧完成，与离线脚本一致。
     * 超时读契约 {@code embedding.timeout_ms}（60s）。
     */
    List<float[]> embedKnowledgeTexts(List<AgentClient.EmbedTextItem> texts) {
        EmbedRequest request = new EmbedRequest(texts.stream()
                .map(t -> new EmbedTextItem(t.title(), t.content()))
                .toList());
        EmbedResponse response;
        try {
            response = webClient
                    .post()
                    .uri("/api/agent/knowledge/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(request))
                    .retrieve()
                    .bodyToMono(EmbedResponse.class)
                    .block(Duration.ofMillis(60000));
        } catch (WebClientResponseException e) {
            throw mapError(e);
        } catch (RuntimeException e) {
            if (AgentTimeouts.causedByTimeout(e)) {
                throw new ApiException(504, MODEL_TIMEOUT, "向量计算服务响应超时");
            }
            throw new ApiException(502, AGENT_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        if (response == null || response.vectors() == null || response.vectors().isEmpty()) {
            throw new ApiException(502, AGENT_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        return response.vectors();
    }

    private ApiException mapError(WebClientResponseException error) {
        String code = null;
        try {
            code = objectMapper
                    .readTree(error.getResponseBodyAsString())
                    .path("detail")
                    .path("code")
                    .asText(null);
        } catch (Exception ignored) {
            // 只提取白名单错误码，不记录可能含医学内容的 Agent 原始响应。
        }
        if (!errorCodes.contains(code)) {
            code = error.getStatusCode().value() == 504 ? MODEL_TIMEOUT : AGENT_UNAVAILABLE;
        }
        int status = MODEL_TIMEOUT.equals(code) ? 504 : (error.getStatusCode().is4xxClientError() ? 422 : 502);
        String message = MODEL_TIMEOUT.equals(code) ? "向量计算服务响应超时" : UNAVAILABLE_MESSAGE;
        return new ApiException(status, code, message);
    }

    /** 请求体：texts 列表（1-50 项）。 */
    record EmbedRequest(@JsonProperty("texts") List<EmbedTextItem> texts) {}

    /** 单条 embedding 输入：title + content。 */
    record EmbedTextItem(@JsonProperty("title") String title, @JsonProperty("content") String content) {}

    /** 响应体：向量列表（顺序与输入一致）。 */
    record EmbedResponse(@JsonProperty("vectors") List<float[]> vectors) {}
}

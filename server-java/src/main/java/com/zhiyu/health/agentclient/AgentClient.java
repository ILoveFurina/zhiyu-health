package com.zhiyu.health.agentclient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.health.HealthProfileService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * server-py 能力入口。调用方只依赖这一稳定门面；chat、vision、voice、clinical 与 knowledge-graph
 * 的协议、超时和错误映射分别由对应能力类维护。
 */
@Component
public class AgentClient {
    private final ChatAgentApi chat;
    private final ClinicalAgentApi clinical;
    private final KnowledgeGraphAgentApi knowledgeGraph;
    private final VisionAgentApi vision;
    private final VoiceAgentApi voice;
    private final KnowledgeEmbeddingAgentApi knowledgeEmbedding;

    public AgentClient(
            WebClient.Builder builder,
            @Value("${zhiyu.agent.base-url}") String baseUrl,
            @Value("${zhiyu.agent.callback-secret}") String callbackSecret,
            ObjectMapper objectMapper,
            Contracts contracts) {
        WebClient webClient = builder.baseUrl(baseUrl)
                .defaultHeader("X-Agent-Callback-Token", callbackSecret)
                .build();
        this.chat = new ChatAgentApi(webClient);
        this.clinical = new ClinicalAgentApi(webClient);
        this.knowledgeGraph = new KnowledgeGraphAgentApi(webClient);
        this.vision = new VisionAgentApi(webClient, objectMapper, contracts);
        this.voice = new VoiceAgentApi(webClient, objectMapper, contracts);
        this.knowledgeEmbedding = new KnowledgeEmbeddingAgentApi(webClient, objectMapper, contracts);
    }

    public Flux<ServerSentEvent<String>> chat(Map<String, Object> requestBody) {
        return chat.chat(requestBody);
    }

    public Flux<ServerSentEvent<String>> medicationKnowledge(String drugName) {
        return chat.medicationKnowledge(drugName);
    }

    public ClinicalResponse explainPrescription(List<Map<String, String>> items) {
        return clinical.explainPrescription(items);
    }

    public ClinicalResponse summarizeConsultation(String diagnosis, String advice) {
        return clinical.summarizeConsultation(diagnosis, advice);
    }

    public GraphProjection fetchGraphProjection() {
        return knowledgeGraph.projection();
    }

    public JsonNode fetchGraphNodeDetail(String nodeId) {
        return knowledgeGraph.nodeDetail(nodeId);
    }

    public VisionResponse interpretVision(
            List<MultipartFile> files, HealthProfileService.AgentProfileContext healthProfile, String scenario) {
        return vision.interpret(files, healthProfile, scenario);
    }

    MultiValueMap<String, HttpEntity<?>> buildVisionMultipart(
            List<MultipartFile> files, HealthProfileService.AgentProfileContext healthProfile, String scenario) {
        return vision.buildMultipart(files, healthProfile, scenario);
    }

    public String recognizeSpeech(MultipartFile audio) {
        return voice.recognize(audio);
    }

    public byte[] synthesizeSpeech(String text) {
        return voice.synthesize(text);
    }

    /**
     * 批量计算知识文档 embedding（ADR-0036）：输入 (title, content) 列表，
     * 返回向量列表。server-java 切分后调本方法，再写 knowledge_chunks。
     */
    public List<float[]> embedKnowledgeTexts(List<EmbedTextItem> texts) {
        return knowledgeEmbedding.embedKnowledgeTexts(texts);
    }

    /** 单条 embedding 输入：title + content，由 server-py 拼 {@code title。content} 后调方舟。 */
    public record EmbedTextItem(String title, String content) {}

    public record VisionResponse(
            JsonNode result,
            String disclaimer,
            @JsonProperty("tcm_disclaimer") String tcmDisclaimer,
            @JsonProperty("page_count") Integer pageCount) {}

    public record ClinicalResponse(String content, String disclaimer) {}

    public record AsrResponse(String text) {}

    public record GraphProjection(List<GraphProjectionNode> nodes, List<GraphProjectionEdge> edges) {}

    public record GraphProjectionNode(String id, String label, String group) {}

    public record GraphProjectionEdge(String source, String target, String type) {}

    public static final class VisionAgentException extends RuntimeException {
        private final String code;
        private final int status;

        public VisionAgentException(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    public static final class VoiceAgentException extends RuntimeException {
        private final String code;
        private final int status;

        public VoiceAgentException(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }
}

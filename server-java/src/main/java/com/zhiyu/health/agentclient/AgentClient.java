package com.zhiyu.health.agentclient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.HealthProfileService;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

/** 调 server-py（Agent 层）的 SSE 客户端 */
@Component
public class AgentClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Contracts contracts;
    /** server-py 可产出码的白名单：唯一事实源是 contracts/vision-errors.json。 */
    private final Set<String> visionErrorCodes;
    /** server-py 语音可产出码的白名单：唯一事实源是 contracts/voice.json（票 45）。 */
    private final Set<String> voiceErrorCodes;
    /** 模型超时码（契约内码）：状态映射的判定点，取值由 ContractsConsistencyTest 钉死。 */
    private static final String CODE_MODEL_TIMEOUT = "VISION_MODEL_TIMEOUT";
    /** server-py 不可达时的本端兜底码：不在契约白名单内（契约只列 server-py 可产出码）。 */
    private static final String CODE_AGENT_UNAVAILABLE = "VISION_AGENT_UNAVAILABLE";

    private static final String MSG_AGENT_UNAVAILABLE = "报告解读服务暂不可用";
    /** 语音未配置码（契约内码）：出口走降级文案，不报错给用户（票 45）。 */
    private static final String CODE_VOICE_UNCONFIGURED = "VOICE_UNCONFIGURED";
    /** 语音音频无效码（契约内码）：空音频/不可读。 */
    private static final String CODE_VOICE_AUDIO_INVALID = "VOICE_AUDIO_INVALID";
    /** 语音模型超时码（契约内码）。 */
    private static final String CODE_VOICE_TIMEOUT = "VOICE_MODEL_TIMEOUT";
    /** 语音服务不可达时的本端兜底码：不在契约白名单内。 */
    private static final String CODE_VOICE_UNAVAILABLE = "VOICE_AGENT_UNAVAILABLE";

    public AgentClient(
            WebClient.Builder builder,
            @Value("${zhiyu.agent.base-url}") String baseUrl,
            @Value("${zhiyu.agent.callback-secret}") String callbackSecret,
            ObjectMapper objectMapper,
            Contracts contracts) {
        this.webClient = builder.baseUrl(baseUrl)
                .defaultHeader("X-Agent-Callback-Token", callbackSecret)
                .build();
        this.objectMapper = objectMapper;
        this.contracts = contracts;
        this.visionErrorCodes = Set.copyOf(contracts.visionErrors().codes());
        this.voiceErrorCodes = Set.copyOf(contracts.voice().errorCodes());
    }

    /** 发起对话请求，返回 SSE 事件流 */
    public Flux<ServerSentEvent<String>> chat(Map<String, Object> requestBody) {
        return webClient
                .post()
                .uri("/api/agent/chat")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    /** 通用药品说明书流（票 51，ADR-0028）：SSE token×N → done，内容来自 LLM 通用语料。 */
    public Flux<ServerSentEvent<String>> medicationKnowledge(String drugName) {
        return webClient
                .post()
                .uri("/api/agent/medication/knowledge")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("drug_name", drugName))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    public ClinicalResponse explainPrescription(List<Map<String, String>> items) {
        return clinicalText("/api/agent/clinical/prescription-explanation", Map.of("items", items));
    }

    public ClinicalResponse summarizeConsultation(String diagnosis, String advice) {
        return clinicalText(
                "/api/agent/clinical/consultation-summary", Map.of("diagnosis", diagnosis, "advice", advice));
    }

    /** 同步获取知识图谱投影（ADR-0013 决策 2）：server-java 鉴权后转调 server-py 只读接口。 */
    public GraphProjection fetchGraphProjection() {
        try {
            GraphProjection response = webClient
                    .get()
                    .uri("/api/knowledge/graph")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(GraphProjection.class)
                    .block(Duration.ofSeconds(15));
            if (response == null) {
                return new GraphProjection(List.of(), List.of());
            }
            return response;
        } catch (RuntimeException e) {
            // server-py 不可达或超时：返回空图降级展示，不阻断 B 端页面
            return new GraphProjection(List.of(), List.of());
        }
    }

    /** 同步获取图谱节点详情：点击节点时另取属性（grilling 决策 6）。
     *  server-py 不可达时返回 null（B 端展示"无详情"），与投影降级纪律对称。 */
    public JsonNode fetchGraphNodeDetail(String nodeId) {
        try {
            return webClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/knowledge/graph/node")
                            .queryParam("node_id", nodeId)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(15));
        } catch (RuntimeException e) {
            // server-py 不可达或超时：返回 null，B 端展示"无详情"，不阻断页面
            return null;
        }
    }

    private ClinicalResponse clinicalText(String uri, Map<String, ?> body) {
        try {
            ClinicalResponse response = webClient
                    .post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(ClinicalResponse.class)
                    .block(Duration.ofSeconds(70));
            if (response == null
                    || response.content() == null
                    || response.content().isBlank()) {
                throw new ApiException(502, "AI 内容生成暂不可用");
            }
            return response;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(502, "AI 内容生成暂不可用");
        }
    }

    /**
     * 同步视觉分析；调用方确保该网络等待不处于数据库事务中。
     * scenario 参数化（票 15）：REPORT 走报告解读，SKIN 走皮肤分析，由调用方按场景传入。
     */
    public VisionResponse interpretVision(
            List<MultipartFile> files, HealthProfileService.AgentProfileContext healthProfile, String scenario) {
        VisionResponse response;
        try {
            response = webClient
                    .post()
                    .uri("/api/agent/vision/interpret")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromMultipartData(buildVisionMultipart(files, healthProfile, scenario)))
                    .retrieve()
                    .bodyToMono(VisionResponse.class)
                    // server-py 最多进行两次 150 秒结构校验调用，预留少量传输时间。
                    .block(Duration.ofSeconds(320));
        } catch (WebClientResponseException e) {
            throw mapVisionError(e);
        } catch (RuntimeException e) {
            if (causedByTimeout(e)) {
                throw new VisionAgentException(CODE_MODEL_TIMEOUT, 504, visionMessage(CODE_MODEL_TIMEOUT));
            }
            throw new VisionAgentException(CODE_AGENT_UNAVAILABLE, 502, MSG_AGENT_UNAVAILABLE);
        }
        if (response == null) {
            throw new VisionAgentException(CODE_AGENT_UNAVAILABLE, 502, MSG_AGENT_UNAVAILABLE);
        }
        return response;
    }

    /**
     * 组装视觉接口 multipart（票 46）：无激活健康档案是合法业务状态，此时必须省略
     * health_profile part——序列化 null 会得到字面 "null"，server-py 无法按档案对象校验。
     */
    MultiValueMap<String, HttpEntity<?>> buildVisionMultipart(
            List<MultipartFile> files, HealthProfileService.AgentProfileContext healthProfile, String scenario) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("scenario", scenario);
        try {
            if (healthProfile != null) {
                body.part("health_profile", objectMapper.writeValueAsString(healthProfile));
            }
            for (MultipartFile file : files) {
                String filename = file.getOriginalFilename() == null ? "report" : file.getOriginalFilename();
                ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
                body.part("files", resource).contentType(MediaType.parseMediaType(file.getContentType()));
            }
        } catch (IOException e) {
            throw new ApiException(422, "报告文件无法读取");
        }
        return body.build();
    }

    /**
     * 语音识别（票 45，ADR-0020）：转发录音 multipart 至 server-py /api/agent/asr，回识别文字。
     * ASR/TTS 不进 agent_call_logs trace；调用方负责入口审计（调用类型+参数类型+结果码/长度，
     * 不记音频与识别文字原文，硬约束 5）。未配置/超时/失败抛 VoiceAgentException 携稳定码。
     */
    public String recognizeSpeech(MultipartFile audio) {
        Contracts.Voice voice = contracts.voice();
        // 契约开关前置：未配置直接降级，不发起网络调用（省请求、避免 server-py 503 噪音）
        if (!voice.asrEnabled()) {
            throw new VoiceAgentException(CODE_VOICE_UNCONFIGURED, 503, voice.degradeHint());
        }
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        try {
            String filename = audio.getOriginalFilename() == null ? "voice" : audio.getOriginalFilename();
            ByteArrayResource resource = new ByteArrayResource(audio.getBytes()) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            body.part("files", resource)
                    .contentType(MediaType.parseMediaType(
                            audio.getContentType() == null ? "application/octet-stream" : audio.getContentType()));
        } catch (IOException e) {
            throw new VoiceAgentException(CODE_VOICE_AUDIO_INVALID, 422, voice.degradeHint());
        }
        AsrResponse response;
        try {
            response = webClient
                    .post()
                    .uri("/api/agent/asr")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(AsrResponse.class)
                    .block(Duration.ofMillis(voice.asrTimeoutMs()));
        } catch (WebClientResponseException e) {
            throw mapVoiceError(e);
        } catch (RuntimeException e) {
            if (causedByTimeout(e)) {
                throw new VoiceAgentException(CODE_VOICE_TIMEOUT, 504, voice.degradeHint());
            }
            throw new VoiceAgentException(CODE_VOICE_UNAVAILABLE, 502, voice.degradeHint());
        }
        if (response == null || response.text() == null || response.text().isBlank()) {
            throw new VoiceAgentException(CODE_VOICE_UNAVAILABLE, 502, voice.degradeHint());
        }
        return response.text();
    }

    /**
     * 语音合成（票 45，ADR-0020）：转发 text 至 server-py /api/agent/tts，回二进制音频。
     * 按需点击触发、整条回复一次合成（MVP 简单）；音频全程内存流转不持久化。
     * 未配置/超时/失败抛 VoiceAgentException 携稳定码。
     */
    public byte[] synthesizeSpeech(String text) {
        Contracts.Voice voice = contracts.voice();
        if (!voice.ttsEnabled()) {
            throw new VoiceAgentException(CODE_VOICE_UNCONFIGURED, 503, voice.degradeHint());
        }
        byte[] audio;
        try {
            audio = webClient
                    .post()
                    .uri("/api/agent/tts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.ALL)
                    .bodyValue(Map.of("text", text))
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofMillis(voice.ttsTimeoutMs()));
        } catch (WebClientResponseException e) {
            throw mapVoiceError(e);
        } catch (RuntimeException e) {
            if (causedByTimeout(e)) {
                throw new VoiceAgentException(CODE_VOICE_TIMEOUT, 504, voice.degradeHint());
            }
            throw new VoiceAgentException(CODE_VOICE_UNAVAILABLE, 502, voice.degradeHint());
        }
        if (audio == null || audio.length == 0) {
            throw new VoiceAgentException(CODE_VOICE_UNAVAILABLE, 502, voice.degradeHint());
        }
        return audio;
    }

    private VoiceAgentException mapVoiceError(WebClientResponseException error) {
        String code = null;
        try {
            code = objectMapper
                    .readTree(error.getResponseBodyAsString())
                    .path("detail")
                    .path("code")
                    .asText(null);
        } catch (Exception ignored) {
            // 响应体仅用于提取白名单错误码，不记录语音原始内容。
        }
        if (!voiceErrorCodes.contains(code)) {
            code = error.getStatusCode().value() == 504 ? CODE_VOICE_TIMEOUT : CODE_VOICE_UNAVAILABLE;
        }
        int status = CODE_VOICE_TIMEOUT.equals(code)
                ? 504
                : (CODE_VOICE_UNCONFIGURED.equals(code)
                        ? 503
                        : (CODE_VOICE_AUDIO_INVALID.equals(code)
                                ? 422
                                : (error.getStatusCode().is4xxClientError() ? 422 : 502)));
        return new VoiceAgentException(code, status, contracts.voice().degradeHint());
    }

    private VisionAgentException mapVisionError(WebClientResponseException error) {
        String code = null;
        try {
            code = objectMapper
                    .readTree(error.getResponseBodyAsString())
                    .path("detail")
                    .path("code")
                    .asText(null);
        } catch (Exception ignored) {
            // 响应体仅用于提取白名单错误码，不记录 Agent 原始错误内容。
        }
        if (!visionErrorCodes.contains(code)) {
            code = error.getStatusCode().value() == 504 ? CODE_MODEL_TIMEOUT : CODE_AGENT_UNAVAILABLE;
        }
        int status = CODE_MODEL_TIMEOUT.equals(code)
                ? 504
                : (CODE_AGENT_UNAVAILABLE.equals(code)
                        ? 502
                        : (error.getStatusCode().is4xxClientError() ? 422 : 502));
        return new VisionAgentException(code, status, visionMessage(code));
    }

    /** 用户可见文案以 contracts/vision-errors.json 为准（原 switch 是手工维护的第二份）。 */
    private String visionMessage(String code) {
        if (CODE_AGENT_UNAVAILABLE.equals(code)) {
            return MSG_AGENT_UNAVAILABLE;
        }
        String message = contracts.visionErrors().messages().get(code);
        return message == null ? "报告文件无法可靠读取，请检查后重试" : message;
    }

    private boolean causedByTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public record VisionResponse(
            JsonNode result,
            String disclaimer,
            @JsonProperty("tcm_disclaimer") String tcmDisclaimer,
            @JsonProperty("page_count") Integer pageCount) {}

    public record ClinicalResponse(String content, String disclaimer) {}

    /** ASR 识别回执（票 45）：text 为识别文字，不落库、不记原文审计。 */
    public record AsrResponse(String text) {}

    /** 图谱投影骨架（ADR-0013 决策 6）：最小拓扑 {nodes, edges}，不携带节点属性。 */
    public record GraphProjection(List<GraphProjectionNode> nodes, List<GraphProjectionEdge> edges) {}

    /** 投影节点：id 为 {label_type}:{natural_key} 复合形式，group 取 label 名用于着色。 */
    public record GraphProjectionNode(String id, String label, String group) {}

    /** 投影边：source/target 为节点 id，type 为关系类型（INDICATES/TREATED_BY 等）。 */
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

    /**
     * 语音调用失败（票 45，ADR-0020）：携带契约 error_codes 中的稳定码，供 controller 出口映射。
     * message 一律用契约 degradeHint（用户可见降级文案），绝不暴露 server-py 原始错误。
     */
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

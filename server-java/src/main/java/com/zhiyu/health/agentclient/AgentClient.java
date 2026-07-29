package com.zhiyu.health.agentclient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** 调 server-py（Agent 层）的 SSE 客户端 */
@Component
public class AgentClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private static final Set<String> VISION_ERROR_CODES = Set.of(
            "VISION_FILE_UNREADABLE", "VISION_FILE_TOO_LARGE", "VISION_FILE_TYPE_INVALID",
            "VISION_FILE_COUNT_INVALID", "VISION_PDF_ENCRYPTED", "VISION_PDF_PAGE_LIMIT",
            "VISION_IMAGE_PIXELS_EXCEEDED", "VISION_OUTPUT_INVALID", "VISION_MODEL_TIMEOUT",
            "VISION_SCENARIO_UNSUPPORTED", "VISION_REPORT_SCOPE_UNSUPPORTED");

    public AgentClient(WebClient.Builder builder,
                       @Value("${zhiyu.agent.base-url}") String baseUrl,
                       @Value("${zhiyu.agent.callback-secret}") String callbackSecret,
                       ObjectMapper objectMapper) {
        this.webClient = builder.baseUrl(baseUrl)
                .defaultHeader("X-Agent-Callback-Token", callbackSecret)
                .build();
        this.objectMapper = objectMapper;
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

    /** 同步报告解读；调用方确保该网络等待不处于数据库事务中。 */
    public VisionResponse interpretVision(List<MultipartFile> files) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("scenario", "REPORT");
        try {
            for (MultipartFile file : files) {
                String filename = file.getOriginalFilename() == null ? "report" : file.getOriginalFilename();
                ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
                body.part("files", resource)
                        .contentType(MediaType.parseMediaType(file.getContentType()));
            }
        } catch (IOException e) {
            throw new ApiException(422, "报告文件无法读取");
        }
        VisionResponse response;
        try {
            response = webClient.post()
                    .uri("/api/agent/vision/interpret")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(VisionResponse.class)
                    // server-py 最多进行两次 150 秒结构校验调用，预留少量传输时间。
                    .block(Duration.ofSeconds(320));
        } catch (WebClientResponseException e) {
            throw mapVisionError(e);
        } catch (RuntimeException e) {
            if (causedByTimeout(e)) {
                throw new VisionAgentException(
                        "VISION_MODEL_TIMEOUT", 504, "报告解读服务响应超时");
            }
            throw new VisionAgentException(
                    "VISION_AGENT_UNAVAILABLE", 502, "报告解读服务暂不可用");
        }
        if (response == null) {
            throw new VisionAgentException(
                    "VISION_AGENT_UNAVAILABLE", 502, "报告解读服务无响应");
        }
        return response;
    }

    private VisionAgentException mapVisionError(WebClientResponseException error) {
        String code = null;
        try {
            code = objectMapper.readTree(error.getResponseBodyAsString())
                    .path("detail").path("code").asText(null);
        } catch (Exception ignored) {
            // 响应体仅用于提取白名单错误码，不记录 Agent 原始错误内容。
        }
        if (!VISION_ERROR_CODES.contains(code)) {
            code = error.getStatusCode().value() == 504
                    ? "VISION_MODEL_TIMEOUT" : "VISION_AGENT_UNAVAILABLE";
        }
        String message = switch (code) {
            case "VISION_MODEL_TIMEOUT" -> "报告解读服务响应超时";
            case "VISION_OUTPUT_INVALID" -> "本次未能生成可靠的结构化解读，请重试";
            case "VISION_REPORT_SCOPE_UNSUPPORTED" -> "请上传报告文字页，暂不支持原始医学影像诊断";
            case "VISION_FILE_TOO_LARGE", "VISION_IMAGE_PIXELS_EXCEEDED" -> "报告文件超出处理限制，请拆分或压缩后上传";
            case "VISION_AGENT_UNAVAILABLE" -> "报告解读服务暂不可用";
            default -> "报告文件无法可靠读取，请检查后重试";
        };
        int status = code.equals("VISION_MODEL_TIMEOUT") ? 504
                : (code.equals("VISION_AGENT_UNAVAILABLE")
                ? 502 : (error.getStatusCode().is4xxClientError() ? 422 : 502));
        return new VisionAgentException(code, status, message);
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
            @JsonProperty("page_count") Integer pageCount) {
    }

    public static final class VisionAgentException extends RuntimeException {
        private final String code;
        private final int status;

        public VisionAgentException(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() { return code; }
        public int status() { return status; }
    }
}

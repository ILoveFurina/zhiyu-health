package com.zhiyu.health.agentclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.health.HealthProfileService;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** 报告、皮肤、饮食、舌苔和药盒共用的视觉调用与错误契约。 */
final class VisionAgentApi {
    private static final String MODEL_TIMEOUT = "VISION_MODEL_TIMEOUT";
    private static final String AGENT_UNAVAILABLE = "VISION_AGENT_UNAVAILABLE";
    private static final String UNAVAILABLE_MESSAGE = "报告解读服务暂不可用";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Contracts contracts;
    private final Set<String> errorCodes;

    VisionAgentApi(WebClient webClient, ObjectMapper objectMapper, Contracts contracts) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.contracts = contracts;
        this.errorCodes = Set.copyOf(contracts.visionErrors().codes());
    }

    AgentClient.VisionResponse interpret(
            List<MultipartFile> files, HealthProfileService.AgentProfileContext profile, String scenario) {
        AgentClient.VisionResponse response;
        try {
            response = webClient
                    .post()
                    .uri("/api/agent/vision/interpret")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromMultipartData(buildMultipart(files, profile, scenario)))
                    .retrieve()
                    .bodyToMono(AgentClient.VisionResponse.class)
                    .block(Duration.ofSeconds(320));
        } catch (WebClientResponseException e) {
            throw mapError(e);
        } catch (RuntimeException e) {
            if (AgentTimeouts.causedByTimeout(e)) {
                throw new AgentClient.VisionAgentException(MODEL_TIMEOUT, 504, message(MODEL_TIMEOUT));
            }
            throw new AgentClient.VisionAgentException(AGENT_UNAVAILABLE, 502, UNAVAILABLE_MESSAGE);
        }
        if (response == null) {
            throw new AgentClient.VisionAgentException(AGENT_UNAVAILABLE, 502, UNAVAILABLE_MESSAGE);
        }
        return response;
    }

    MultiValueMap<String, HttpEntity<?>> buildMultipart(
            List<MultipartFile> files, HealthProfileService.AgentProfileContext profile, String scenario) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("scenario", scenario);
        try {
            if (profile != null) {
                body.part("health_profile", objectMapper.writeValueAsString(profile));
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

    private AgentClient.VisionAgentException mapError(WebClientResponseException error) {
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
        int status = MODEL_TIMEOUT.equals(code)
                ? 504
                : (AGENT_UNAVAILABLE.equals(code) ? 502 : (error.getStatusCode().is4xxClientError() ? 422 : 502));
        return new AgentClient.VisionAgentException(code, status, message(code));
    }

    private String message(String code) {
        if (AGENT_UNAVAILABLE.equals(code)) {
            return UNAVAILABLE_MESSAGE;
        }
        return contracts.visionErrors().messages().getOrDefault(code, "报告文件无法可靠读取，请检查后重试");
    }
}

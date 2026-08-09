package com.zhiyu.health.agentclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.Contracts;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** ASR/TTS 调用；音频和识别原文只在内存流转，不进入 trace。 */
final class VoiceAgentApi {
    private static final String UNCONFIGURED = "VOICE_UNCONFIGURED";
    private static final String AUDIO_INVALID = "VOICE_AUDIO_INVALID";
    private static final String MODEL_TIMEOUT = "VOICE_MODEL_TIMEOUT";
    private static final String AGENT_UNAVAILABLE = "VOICE_AGENT_UNAVAILABLE";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Contracts contracts;
    private final Set<String> errorCodes;

    VoiceAgentApi(WebClient webClient, ObjectMapper objectMapper, Contracts contracts) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.contracts = contracts;
        this.errorCodes = Set.copyOf(contracts.voice().errorCodes());
    }

    String recognize(MultipartFile audio) {
        Contracts.Voice voice = contracts.voice();
        if (!voice.asrEnabled()) {
            throw failure(UNCONFIGURED, 503);
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
            throw failure(AUDIO_INVALID, 422);
        }
        AgentClient.AsrResponse response;
        try {
            response = webClient
                    .post()
                    .uri("/api/agent/asr")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(AgentClient.AsrResponse.class)
                    .block(Duration.ofMillis(voice.asrTimeoutMs()));
        } catch (WebClientResponseException e) {
            throw mapError(e);
        } catch (RuntimeException e) {
            throw AgentTimeouts.causedByTimeout(e) ? failure(MODEL_TIMEOUT, 504) : failure(AGENT_UNAVAILABLE, 502);
        }
        if (response == null || response.text() == null || response.text().isBlank()) {
            throw failure(AGENT_UNAVAILABLE, 502);
        }
        return response.text();
    }

    byte[] synthesize(String text) {
        Contracts.Voice voice = contracts.voice();
        if (!voice.ttsEnabled()) {
            throw failure(UNCONFIGURED, 503);
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
            throw mapError(e);
        } catch (RuntimeException e) {
            throw AgentTimeouts.causedByTimeout(e) ? failure(MODEL_TIMEOUT, 504) : failure(AGENT_UNAVAILABLE, 502);
        }
        if (audio == null || audio.length == 0) {
            throw failure(AGENT_UNAVAILABLE, 502);
        }
        return audio;
    }

    private AgentClient.VoiceAgentException mapError(WebClientResponseException error) {
        String code = null;
        try {
            code = objectMapper
                    .readTree(error.getResponseBodyAsString())
                    .path("detail")
                    .path("code")
                    .asText(null);
        } catch (Exception ignored) {
            // 只提取白名单码，不记录音频或识别原文。
        }
        if (!errorCodes.contains(code)) {
            code = error.getStatusCode().value() == 504 ? MODEL_TIMEOUT : AGENT_UNAVAILABLE;
        }
        int status = MODEL_TIMEOUT.equals(code)
                ? 504
                : (UNCONFIGURED.equals(code)
                        ? 503
                        : (AUDIO_INVALID.equals(code)
                                ? 422
                                : (error.getStatusCode().is4xxClientError() ? 422 : 502)));
        return failure(code, status);
    }

    private AgentClient.VoiceAgentException failure(String code, int status) {
        return new AgentClient.VoiceAgentException(
                code, status, contracts.voice().degradeHint());
    }
}

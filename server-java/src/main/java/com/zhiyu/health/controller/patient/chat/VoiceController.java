package com.zhiyu.health.controller.patient.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.chat.VoiceService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * C 端语音双向入口（票 45，ADR-0020）：只做校验与装配，业务在 VoiceService。
 *
 * ASR：multipart 音频 -> server-java（鉴权、审计）-> server-py -> 火山 ASR -> 文字回端侧填
 * 输入框（可见可改、不自动发）。TTS：按需点击触发 -> server-java -> server-py -> 火山 TTS
 * -> 二进制逐跳透传 -> 端侧播放/停止。ASR/TTS 不进 agent_call_logs trace，仅入口审计。
 */
@Validated
@RestController
@RequestMapping("/api/c")
@RequiredArgsConstructor
public class VoiceController {

    private final VoiceService service;

    /** 语音识别：录音 multipart 转发 server-py，回识别文字填输入框。 */
    @PostMapping("/asr")
    public VoiceService.AsrResult recognize(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new com.zhiyu.health.config.ApiException(422, "VOICE_AUDIO_INVALID");
        }
        return service.recognize(audio, patientId);
    }

    /**
     * 语音合成：按 message_id/text 转发 server-py，回二进制音频逐跳透传。
     * POST：契约对称、curl 友好（body 传 text）；GET：小程序 my.downloadFile 拉
     * 取音频临时文件（query 传 text，音频下载幂等，适合 GET）。
     */
    @PostMapping(value = "/tts", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<ByteArrayResource> synthesize(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @Validated @RequestBody TtsRequest request) {
        return ttsResponse(request.text(), request.messageId(), patientId);
    }

    /** GET /c/tts：query 传 text，供小程序 my.downloadFile 拉取音频文件播放。 */
    @GetMapping(value = "/tts", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<ByteArrayResource> synthesizeGet(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @RequestParam @NotBlank @Size(max = 2000) String text,
            @RequestParam(value = "message_id", required = false) Long messageId) {
        return ttsResponse(text, messageId, patientId);
    }

    private ResponseEntity<ByteArrayResource> ttsResponse(String text, Long messageId, Long patientId) {
        VoiceService.TtsResult result = service.synthesize(text, messageId, patientId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, result.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(result.audio().length))
                .body(new ByteArrayResource(result.audio()));
    }

    /**
     * TTS 请求体：合成文本（整条回复一次合成，MVP 简单不分段）+ 可选 message_id 用于审计关联。
     * text 长度上限与 server-py schemas/voice.py 对齐（2000）。
     */
    public record TtsRequest(@NotBlank @Size(max = 2000) String text, @JsonProperty("message_id") Long messageId) {}
}

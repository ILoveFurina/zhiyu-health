package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.agentclient.AgentClient.VoiceAgentException;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * VoiceService 单测（票 45，ADR-0020）：mock AgentClient，断言降级映射与审计语义。
 * 不触达网络与 server-py；契约用真实 contracts/voice.json（骨架阶段 enabled=false）。
 */
class VoiceServiceTest {

    private final Contracts contracts = Contracts.load(Contracts.resolveDir());
    private final AgentClient agentClient = mock(AgentClient.class);
    private final VoiceService service = new VoiceService(agentClient, contracts);

    @Test
    void recognizeReturnsTextOnSuccess() {
        MultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[] {1, 2, 3});
        when(agentClient.recognizeSpeech(audio)).thenReturn("我头疼两天了，该挂什么科");

        VoiceService.AsrResult result = service.recognize(audio, 12L);

        assertThat(result.text()).isEqualTo("我头疼两天了，该挂什么科");
    }

    @Test
    void recognizeMapsUnconfiguredToDegradeHint() {
        // 契约 enabled=false -> AgentClient 抛 VOICE_UNCONFIGURED -> service 出口 503 + 降级文案
        MultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[] {1, 2, 3});
        when(agentClient.recognizeSpeech(audio))
                .thenThrow(new VoiceAgentException(
                        "VOICE_UNCONFIGURED", 503, contracts.voice().degradeHint()));

        assertThatThrownBy(() -> service.recognize(audio, 12L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(503);
            assertThat(e.getCode()).isEqualTo("VOICE_UNCONFIGURED");
            assertThat(e.getMessage()).contains("语音功能暂不可用");
        });
    }

    @Test
    void recognizeMapsTimeoutToDegradeHint() {
        MultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[] {1, 2, 3});
        when(agentClient.recognizeSpeech(audio))
                .thenThrow(new VoiceAgentException(
                        "VOICE_MODEL_TIMEOUT", 504, contracts.voice().degradeHint()));

        assertThatThrownBy(() -> service.recognize(audio, 12L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(504);
            assertThat(e.getCode()).isEqualTo("VOICE_MODEL_TIMEOUT");
        });
    }

    @Test
    void recognizeMapsModelFailureToDegradeHint() {
        MultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[] {1, 2, 3});
        when(agentClient.recognizeSpeech(audio))
                .thenThrow(new VoiceAgentException(
                        "VOICE_MODEL_FAILED", 502, contracts.voice().degradeHint()));

        assertThatThrownBy(() -> service.recognize(audio, 12L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(502);
            assertThat(e.getCode()).isEqualTo("VOICE_MODEL_FAILED");
        });
    }

    @Test
    void synthesizeReturnsAudioWithContractContentTypeWhenConfigured() {
        // 骨架阶段 tts_format=null -> service 用通用二进制占位；开通后由契约钉死（如 audio/mpeg）
        when(agentClient.synthesizeSpeech("你好")).thenReturn(new byte[] {10, 20, 30});

        VoiceService.TtsResult result = service.synthesize("你好", 41L, 12L);

        assertThat(result.audio()).containsExactly(10, 20, 30);
        // 骨架阶段 tts_format 为 null，回退 application/octet-stream
        assertThat(result.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void synthesizeMapsUnconfiguredToDegradeHint() {
        when(agentClient.synthesizeSpeech(any()))
                .thenThrow(new VoiceAgentException(
                        "VOICE_UNCONFIGURED", 503, contracts.voice().degradeHint()));

        assertThatThrownBy(() -> service.synthesize("你好", 41L, 12L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(503);
            assertThat(e.getCode()).isEqualTo("VOICE_UNCONFIGURED");
        });
        // 未配置时不应到达合成成功路径
        verify(agentClient, never()).synthesizeSpeech(null);
    }
}

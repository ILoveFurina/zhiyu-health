package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.service.VoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 语音双向 C 端 HTTP seam（票 45，ADR-0020）：覆盖正常/未配置/超时/失败/空音频。 */
class VoiceControllerTest {

    @Test
    void asrReturnsRecognizedText() throws Exception {
        VoiceService service = mock(VoiceService.class);
        when(service.recognize(any(), any())).thenReturn(new VoiceService.AsrResult("我头疼两天了，该挂什么科"));
        MockMvc mvc = standaloneSetup(new VoiceController(service))
                .setControllerAdvice(new com.zhiyu.health.config.ApiExceptionHandler())
                .build();
        MockMultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[] {1, 2, 3, 4});

        mvc.perform(multipart("/api/c/asr").file(audio).requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("我头疼两天了，该挂什么科"));
    }

    @Test
    void asrEmptyAudioRejectedBeforeServiceCall() throws Exception {
        VoiceService service = mock(VoiceService.class);
        MockMvc mvc = standaloneSetup(new VoiceController(service))
                .setControllerAdvice(new com.zhiyu.health.config.ApiExceptionHandler())
                .build();
        MockMultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[] {});

        mvc.perform(multipart("/api/c/asr").file(audio).requestAttr("authSubject", 12L))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void asrUnconfiguredDegradesToHintWithoutError() throws Exception {
        VoiceService service = mock(VoiceService.class);
        when(service.recognize(any(), any()))
                .thenThrow(new ApiException(503, "VOICE_UNCONFIGURED", "语音功能暂不可用，已切换为文字输入"));
        MockMvc mvc = standaloneSetup(new VoiceController(service))
                .setControllerAdvice(new com.zhiyu.health.config.ApiExceptionHandler())
                .build();
        MockMultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/asr").file(audio).requestAttr("authSubject", 12L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail.code").value("VOICE_UNCONFIGURED"))
                .andExpect(jsonPath("$.detail.message").value("语音功能暂不可用，已切换为文字输入"));
    }

    @Test
    void asrTimeoutDegradesToHint() throws Exception {
        VoiceService service = mock(VoiceService.class);
        when(service.recognize(any(), any()))
                .thenThrow(new ApiException(504, "VOICE_MODEL_TIMEOUT", "语音功能暂不可用，已切换为文字输入"));
        MockMvc mvc = standaloneSetup(new VoiceController(service))
                .setControllerAdvice(new com.zhiyu.health.config.ApiExceptionHandler())
                .build();
        MockMultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/asr").file(audio).requestAttr("authSubject", 12L))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.detail.code").value("VOICE_MODEL_TIMEOUT"));
    }

    @Test
    void asrFailureDegradesToHint() throws Exception {
        VoiceService service = mock(VoiceService.class);
        when(service.recognize(any(), any()))
                .thenThrow(new ApiException(502, "VOICE_MODEL_FAILED", "语音功能暂不可用，已切换为文字输入"));
        MockMvc mvc = standaloneSetup(new VoiceController(service))
                .setControllerAdvice(new com.zhiyu.health.config.ApiExceptionHandler())
                .build();
        MockMultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/asr").file(audio).requestAttr("authSubject", 12L))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail.code").value("VOICE_MODEL_FAILED"));
    }

    @Test
    void ttsReturnsBinaryAudio() throws Exception {
        VoiceService service = mock(VoiceService.class);
        when(service.synthesize(any(), any(), any()))
                .thenReturn(new VoiceService.TtsResult(new byte[] {1, 2, 3, 4}, "audio/mpeg"));
        MockMvc mvc = standaloneSetup(new VoiceController(service))
                .setControllerAdvice(new com.zhiyu.health.config.ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/c/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好，我是小愈\"}")
                        .requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/mpeg"))
                .andExpect(content().bytes(new byte[] {1, 2, 3, 4}));
    }

    @Test
    void ttsUnconfiguredDegradesToHint() throws Exception {
        VoiceService service = mock(VoiceService.class);
        when(service.synthesize(any(), any(), any()))
                .thenThrow(new ApiException(503, "VOICE_UNCONFIGURED", "语音功能暂不可用，已切换为文字输入"));
        MockMvc mvc = standaloneSetup(new VoiceController(service))
                .setControllerAdvice(new com.zhiyu.health.config.ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/c/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}")
                        .requestAttr("authSubject", 12L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail.code").value("VOICE_UNCONFIGURED"));
    }

    @Test
    void ttsRejectsBlankText() throws Exception {
        VoiceService service = mock(VoiceService.class);
        MockMvc mvc = standaloneSetup(new VoiceController(service))
                .setControllerAdvice(new com.zhiyu.health.config.ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/c/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}")
                        .requestAttr("authSubject", 12L))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ttsGetEndpointReturnsBinaryAudioForMiniProgramDownload() throws Exception {
        // 小程序 my.downloadFile 走 GET + query 传 text；音频下载幂等，适合 GET
        VoiceService service = mock(VoiceService.class);
        when(service.synthesize(any(), any(), any()))
                .thenReturn(new VoiceService.TtsResult(new byte[] {5, 6, 7}, "audio/mpeg"));
        MockMvc mvc = standaloneSetup(new VoiceController(service))
                .setControllerAdvice(new com.zhiyu.health.config.ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/c/tts").param("text", "你好").requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/mpeg"))
                .andExpect(content().bytes(new byte[] {5, 6, 7}));
    }
}

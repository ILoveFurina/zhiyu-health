package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.controller.patient.vision.TonguePhotoController;
import com.zhiyu.health.service.vision.TonguePhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 拍舌苔中医辨证的 C 端 HTTP seam。 */
class TonguePhotoControllerTest {

    @Test
    void patientUploadsTonguePhotoAndReceivesStructuredCardWithDualDisclaimer() throws Exception {
        // ADR-0024 第 2 条：舌诊卡片叠加通用免责 + 中医专属免责两条
        TonguePhotoService service = mock(TonguePhotoService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(service.analyze(eq(12L), isNull(), eq("tongue-001"), anyList()))
                .thenReturn(new TonguePhotoService.TongueAnalysisView(
                        7L,
                        objectMapper.readTree(
                                """
                                {"constitution":"气虚质",
                                 "tongue_features":"舌质淡红舌体胖大有齿痕舌苔薄白",
                                 "care_direction":"规律作息可佐山药红枣等日常食材",
                                 "diet_principle":"少食生冷饮食有节",
                                 "urgency_hint":"","need_doctor":false}
                                """),
                        "仅供参考，不替代医生诊断",
                        "体质辨识仅供参考，不替代中医面诊"));
        MockMvc mvc = standaloneSetup(new TonguePhotoController(service)).build();
        MockMultipartFile image = new MockMultipartFile("files", "tongue.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/tongue-photos")
                        .file(image)
                        .param("request_id", "tongue-001")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation_id").value(7))
                .andExpect(jsonPath("$.result.constitution").value("气虚质"))
                .andExpect(jsonPath("$.result.need_doctor").value(false))
                // 通用免责（硬约束 1）
                .andExpect(jsonPath("$.disclaimer").value("仅供参考，不替代医生诊断"))
                // ADR-0024：中医专属免责
                .andExpect(jsonPath("$.tcm_disclaimer").value("体质辨识仅供参考，不替代中医面诊"));
        verify(service).analyze(eq(12L), isNull(), eq("tongue-001"), anyList());
    }

    @Test
    void missingRequestIdIsRejected() throws Exception {
        TonguePhotoService service = mock(TonguePhotoService.class);
        MockMvc mvc = standaloneSetup(new TonguePhotoController(service)).build();
        MockMultipartFile image = new MockMultipartFile("files", "tongue.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/tongue-photos").file(image).requestAttr("authSubject", "12"))
                .andExpect(status().isBadRequest());
    }
}

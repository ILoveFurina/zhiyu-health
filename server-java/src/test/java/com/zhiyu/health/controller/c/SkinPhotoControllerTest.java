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
import com.zhiyu.health.service.SkinPhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 拍皮肤分析的 C 端 HTTP seam。 */
class SkinPhotoControllerTest {

    @Test
    void patientUploadsSkinPhotoAndReceivesStructuredCard() throws Exception {
        SkinPhotoService service = mock(SkinPhotoService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(service.analyze(eq(12L), isNull(), eq("skin-001"), anyList()))
                .thenReturn(new SkinPhotoService.SkinAnalysisView(
                        7L,
                        objectMapper.readTree(
                                """
                                {"skin_type":"偏干性肤质",
                                 "findings":[{"name":"轻度干燥","severity":"green","explanation":"脱屑","care_advice":"保湿"}],
                                 "care_summary":"注意保湿防晒","need_doctor":false}
                                """),
                        "仅供参考，不替代医生诊断"));
        MockMvc mvc = standaloneSetup(new SkinPhotoController(service)).build();
        MockMultipartFile image = new MockMultipartFile("files", "face.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/skin-photos")
                        .file(image)
                        .param("request_id", "skin-001")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation_id").value(7))
                .andExpect(jsonPath("$.result.skin_type").value("偏干性肤质"))
                .andExpect(jsonPath("$.result.findings[0].severity").value("green"))
                .andExpect(jsonPath("$.result.need_doctor").value(false))
                .andExpect(jsonPath("$.disclaimer").value("仅供参考，不替代医生诊断"));
        verify(service).analyze(eq(12L), isNull(), eq("skin-001"), anyList());
    }

    @Test
    void missingRequestIdIsRejected() throws Exception {
        SkinPhotoService service = mock(SkinPhotoService.class);
        MockMvc mvc = standaloneSetup(new SkinPhotoController(service)).build();
        MockMultipartFile image = new MockMultipartFile("files", "face.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/skin-photos").file(image).requestAttr("authSubject", "12"))
                .andExpect(status().isBadRequest());
    }
}

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
import com.zhiyu.health.controller.patient.vision.DietPhotoController;
import com.zhiyu.health.service.vision.DietPhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 拍饮食分析的 C 端 HTTP seam。 */
class DietPhotoControllerTest {

    @Test
    void patientUploadsDietPhotoAndReceivesStructuredCard() throws Exception {
        DietPhotoService service = mock(DietPhotoService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(service.analyze(eq(12L), isNull(), eq("diet-001"), anyList()))
                .thenReturn(new DietPhotoService.DietAnalysisView(
                        7L,
                        objectMapper.readTree(
                                """
                                {"meal_type":"午餐",
                                 "foods":[{"name":"米饭","estimated_amount":"约200g","risk_level":"green","explanation":"主食"}],
                                 "estimated_calories":"约450千卡","nutrition_summary":"碳水为主",
                                 "diet_advice":"增加蔬菜","personal_tip":"","need_doctor":false}
                                """),
                        "仅供参考，不替代医生诊断"));
        MockMvc mvc = standaloneSetup(new DietPhotoController(service)).build();
        MockMultipartFile image = new MockMultipartFile("files", "meal.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/diet-photos")
                        .file(image)
                        .param("request_id", "diet-001")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation_id").value(7))
                .andExpect(jsonPath("$.result.meal_type").value("午餐"))
                .andExpect(jsonPath("$.result.foods[0].risk_level").value("green"))
                .andExpect(jsonPath("$.result.need_doctor").value(false))
                .andExpect(jsonPath("$.disclaimer").value("仅供参考，不替代医生诊断"));
        verify(service).analyze(eq(12L), isNull(), eq("diet-001"), anyList());
    }

    @Test
    void missingRequestIdIsRejected() throws Exception {
        DietPhotoService service = mock(DietPhotoService.class);
        MockMvc mvc = standaloneSetup(new DietPhotoController(service)).build();
        MockMultipartFile image = new MockMultipartFile("files", "meal.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/diet-photos").file(image).requestAttr("authSubject", "12"))
                .andExpect(status().isBadRequest());
    }
}

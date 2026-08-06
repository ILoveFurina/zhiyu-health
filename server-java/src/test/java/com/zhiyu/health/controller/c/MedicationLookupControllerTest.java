package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.service.MedicationLookupService;
import com.zhiyu.health.service.MedicationLookupService.MedicationLookupView;
import com.zhiyu.health.service.PillBoxPhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 拍药盒与文字查药的 C 端 HTTP seam（票 14）。 */
class MedicationLookupControllerTest {

    @Test
    void patientUploadsPillBoxPhotoAndReceivesDualOutputCards() throws Exception {
        // ADR-0025 差异化点 3：双出口 medication_info + medication_safety
        PillBoxPhotoService pillBoxService = mock(PillBoxPhotoService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(pillBoxService.analyze(eq(12L), isNull(), eq("pill-001"), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new MedicationLookupService.MedicationLookupView(
                        7L,
                        objectMapper.readTree(
                                """
                                {"medications":[{"name":"阿莫西林胶囊","generic_name":"阿莫西林",\
                                "specification":"0.25g","instructions":"适应症/用法用量/注意事项"}],\
                                "matched_names":["阿莫西林胶囊"]}"""),
                        objectMapper.readTree(
                                """
                                {"decision":"SAFE","message_type":"contraindication_result",\
                                "blocked":false,"reasons":[],\
                                "message":"未发现当前健康档案与候选药品之间的已知禁忌。",\
                                "advice":null,"medications":[{"name":"阿莫西林胶囊","generic_name":"阿莫西林"}]}"""),
                        false,
                        null,
                        null,
                        "仅供参考，不替代医生诊断"));
        MedicationLookupService lookupService = mock(MedicationLookupService.class);
        MockMvc mvc = standaloneSetup(new MedicationLookupController(pillBoxService, lookupService))
                .build();
        MockMultipartFile image = new MockMultipartFile("files", "box.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/pill-box-photos")
                        .file(image)
                        .param("request_id", "pill-001")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation_id").value(7))
                .andExpect(jsonPath("$.not_found").value(false))
                // 双出口卡片 1：说明书
                .andExpect(jsonPath("$.medication_info.medications[0].name").value("阿莫西林胶囊"))
                .andExpect(jsonPath("$.medication_info.medications[0].instructions")
                        .exists())
                // 双出口卡片 2：安全结果
                .andExpect(jsonPath("$.medication_safety.decision").value("SAFE"))
                .andExpect(jsonPath("$.medication_safety.blocked").value(false));
        verify(pillBoxService).analyze(eq(12L), isNull(), eq("pill-001"), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void patientLooksUpMedicationByNameAndReceivesDualOutputCards() throws Exception {
        // ADR-0025 差异化点 4：文字版入口共用同一查询与规则出口
        PillBoxPhotoService pillBoxService = mock(PillBoxPhotoService.class);
        MedicationLookupService lookupService = mock(MedicationLookupService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(lookupService.lookupAndAppend(eq(12L), isNull(), eq("查药品"), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new MedicationLookupView(
                        7L,
                        objectMapper.readTree(
                                """
                                {"medications":[{"name":"布洛芬胶囊","generic_name":"布洛芬",\
                                "specification":"0.2g","instructions":"解热镇痛"}],\
                                "matched_names":["布洛芬"]}"""),
                        objectMapper.readTree(
                                """
                                {"decision":"BLOCKED","message_type":"contraindication_warning",\
                                "blocked":true,"reasons":["含布洛芬成分，与过敏原存在交叉反应"],\
                                "message":"检测到用药禁忌，已阻止本次药品推荐。请咨询医生或药师后再用药。",\
                                "advice":"请咨询医生或药师，并主动告知完整过敏史和正在使用的药品。",\
                                "medications":[{"name":"布洛芬胶囊","generic_name":"布洛芬"}]}"""),
                        false,
                        null,
                        null,
                        "仅供参考，不替代医生诊断"));
        MockMvc mvc = standaloneSetup(new MedicationLookupController(pillBoxService, lookupService))
                .build();

        mvc.perform(post("/api/c/medication-lookups")
                        .param("request_id", "lookup-001")
                        .param("medication_name", "布洛芬")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation_id").value(7))
                .andExpect(jsonPath("$.not_found").value(false))
                .andExpect(jsonPath("$.medication_info.medications[0].name").value("布洛芬胶囊"))
                .andExpect(jsonPath("$.medication_safety.decision").value("BLOCKED"))
                .andExpect(jsonPath("$.medication_safety.blocked").value(true))
                .andExpect(jsonPath("$.medication_safety.advice").exists());
        verify(lookupService).lookupAndAppend(eq(12L), isNull(), eq("查药品"), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void notFoundCarriesHintAndEmptyAllergyCarriesReminder() throws Exception {
        // 票 46 延伸：notFound 响应携带 hint 供前端展示后端已落库的引导文案;
        // 成功但空过敏史时携带 reminder 引导完善档案。两者经 HTTP seam 序列化透出。
        PillBoxPhotoService pillBoxService = mock(PillBoxPhotoService.class);
        MedicationLookupService lookupService = mock(MedicationLookupService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(pillBoxService.analyze(eq(12L), isNull(), eq("pill-nf"), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new MedicationLookupView(
                        7L, null, null, true, "未找到药品『××』，请核对药名或咨询医生/药师。", null, "仅供参考，不替代医生诊断"));
        when(pillBoxService.analyze(eq(12L), isNull(), eq("pill-remind"), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new MedicationLookupView(
                        7L,
                        objectMapper.readTree("{\"medications\":[{\"name\":\"阿莫西林胶囊\"}]}"),
                        objectMapper.readTree("{\"decision\":\"SAFE\",\"blocked\":false}"),
                        false,
                        null,
                        "你还没有在健康档案中填写过敏史，建议完善后能更准确地判断用药安全。",
                        "仅供参考，不替代医生诊断"));
        MockMvc mvc = standaloneSetup(new MedicationLookupController(pillBoxService, lookupService))
                .build();
        MockMultipartFile image = new MockMultipartFile("files", "box.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/pill-box-photos")
                        .file(image)
                        .param("request_id", "pill-nf")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.not_found").value(true))
                .andExpect(jsonPath("$.hint").value("未找到药品『××』，请核对药名或咨询医生/药师。"));

        mvc.perform(multipart("/api/c/pill-box-photos")
                        .file(image)
                        .param("request_id", "pill-remind")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.not_found").value(false))
                .andExpect(jsonPath("$.reminder").value("你还没有在健康档案中填写过敏史，建议完善后能更准确地判断用药安全。"));
    }

    @Test
    void missingMedicationNameIsRejected() throws Exception {
        PillBoxPhotoService pillBoxService = mock(PillBoxPhotoService.class);
        MedicationLookupService lookupService = mock(MedicationLookupService.class);
        MockMvc mvc = standaloneSetup(new MedicationLookupController(pillBoxService, lookupService))
                .build();

        MockHttpServletRequestBuilder req = post("/api/c/medication-lookups")
                .param("request_id", "lookup-001")
                .requestAttr("authSubject", "12");
        mvc.perform(req).andExpect(status().isBadRequest());
    }
}

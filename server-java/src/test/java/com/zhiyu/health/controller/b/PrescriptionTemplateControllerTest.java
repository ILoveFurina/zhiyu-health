package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.staff.prescription.PrescriptionTemplateController;
import com.zhiyu.health.controller.staff.prescription.mapping.PrescriptionTemplateInputMapper;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.service.prescription.PrescriptionTemplateService;
import com.zhiyu.health.support.StaffTokens;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PrescriptionTemplateController.class)
class PrescriptionTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PrescriptionTemplateService service;

    @MockitoBean
    private PrescriptionTemplateInputMapper inputMapper;

    private static final PrescriptionTemplateService.TemplateView TEMPLATE_VIEW =
            new PrescriptionTemplateService.TemplateView(
                    1L,
                    "高血压基础用药",
                    1L,
                    OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
                    List.of(new PrescriptionTemplateService.ItemView(
                            11L, 12L, "氨氯地平片", "5mg*14片", "5mg", "每日1次", "30天", "晨起服用")));

    private static final String TEMPLATE_JSON =
            """
            {"name":"高血压基础用药","items":[{"medication_id":12,
            "dosage":"5mg","frequency":"每日1次","duration":"30天","notes":"晨起服用"}]}
            """;

    @Test
    void doctorCrudRoundTrip() throws Exception {
        when(service.listTemplates(8L)).thenReturn(List.of(TEMPLATE_VIEW));
        when(service.getDetail(8L, 1L)).thenReturn(TEMPLATE_VIEW);
        when(inputMapper.toSaveCommand(eq(8L), any()))
                .thenReturn(new PrescriptionTemplateService.SaveCommand(
                        8L,
                        "高血压基础用药",
                        List.of(new PrescriptionTemplateService.ItemInput(12L, "5mg", "每日1次", "30天", "晨起服用"))));
        when(service.create(any())).thenReturn(TEMPLATE_VIEW);
        when(service.update(eq(8L), eq(1L), any())).thenReturn(TEMPLATE_VIEW);

        mockMvc.perform(get("/api/b/reception/prescription-templates")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("高血压基础用药"))
                .andExpect(jsonPath("$[0].doctor_id").value(1))
                .andExpect(jsonPath("$[0].created_at").exists())
                .andExpect(jsonPath("$[0].items[0].id").value(11))
                .andExpect(jsonPath("$[0].items[0].medication_id").value(12))
                .andExpect(jsonPath("$[0].items[0].medication_name").value("氨氯地平片"));

        mockMvc.perform(get("/api/b/reception/prescription-templates/1")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].specification").value("5mg*14片"));

        mockMvc.perform(post("/api/b/reception/prescription-templates")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(TEMPLATE_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));

        mockMvc.perform(put("/api/b/reception/prescription-templates/1")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(TEMPLATE_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("高血压基础用药"));

        mockMvc.perform(delete("/api/b/reception/prescription-templates/1")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isNoContent());
        verify(service).delete(8L, 1L);
    }

    @Test
    void rejectsBlankNameAndEmptyItems() throws Exception {
        mockMvc.perform(post("/api/b/reception/prescription-templates")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"name\":\" \",\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void foreignTemplateReturns404() throws Exception {
        // doctor.lin 读/改/删 doctor.zhou 的模板：service 统一 404，不暴露存在性。
        when(service.getDetail(8L, 3L)).thenThrow(new ApiException(404, "处方模板不存在"));
        when(inputMapper.toSaveCommand(eq(8L), any()))
                .thenReturn(new PrescriptionTemplateService.SaveCommand(
                        8L,
                        "高血压基础用药",
                        List.of(new PrescriptionTemplateService.ItemInput(12L, "5mg", "每日1次", "30天", null))));
        when(service.update(eq(8L), eq(3L), any())).thenThrow(new ApiException(404, "处方模板不存在"));
        doThrow(new ApiException(404, "处方模板不存在")).when(service).delete(8L, 3L);

        mockMvc.perform(get("/api/b/reception/prescription-templates/3")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("处方模板不存在"));
        mockMvc.perform(put("/api/b/reception/prescription-templates/3")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(TEMPLATE_JSON))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/b/reception/prescription-templates/3")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonDoctorGets403() throws Exception {
        when(service.listTemplates(1L)).thenThrow(new ApiException(403, "仅医生可操作"));

        mockMvc.perform(get("/api/b/reception/prescription-templates")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅医生可操作"));
    }

    @Test
    void inactiveMedicationGets400() throws Exception {
        when(inputMapper.toSaveCommand(eq(8L), any()))
                .thenReturn(new PrescriptionTemplateService.SaveCommand(
                        8L,
                        "测试模板",
                        List.of(new PrescriptionTemplateService.ItemInput(99L, "5mg", "每日1次", "30天", null))));
        when(service.create(any())).thenThrow(new ApiException(400, "药品不存在或已停用"));

        mockMvc.perform(
                        post("/api/b/reception/prescription-templates")
                                .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                                .contentType("application/json")
                                .content(
                                        """
                        {"name":"测试模板","items":[{"medication_id":99,
                        "dosage":"5mg","frequency":"每日1次","duration":"30天"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("药品不存在或已停用"));
    }
}

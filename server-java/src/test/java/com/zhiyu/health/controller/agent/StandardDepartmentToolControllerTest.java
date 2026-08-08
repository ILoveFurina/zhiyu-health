package com.zhiyu.health.controller.agent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zhiyu.health.config.AgentCallbackAuthFilter;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.service.PatientMedicalDirectoryService;
import com.zhiyu.health.service.PatientMedicalDirectoryService.Coordinates;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/** 智能导诊标准科室工具回调（票 50）：Agent 凭证保护、服务端城市解析、目录与号源卡载荷。 */
class StandardDepartmentToolControllerTest {

    private static final String SECRET = "shared-secret";
    private static final Coordinates COORDS = new Coordinates(34.7572, 113.6458);

    private final PatientMedicalDirectoryService directory = mock(PatientMedicalDirectoryService.class);

    private MockMvc mvc() {
        return standaloneSetup(new StandardDepartmentToolController(directory))
                .addFilter(new AgentCallbackAuthFilter(SECRET))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void callbacksRequireSharedServiceCredential() throws Exception {
        MockMvc mvc = mvc();

        mvc.perform(get("/api/agent/standard-departments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/standard-departments").header(AgentCallbackAuthFilter.HEADER_NAME, "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/standard-departments/1/slots")).andExpect(status().isUnauthorized());
    }

    @Test
    void catalogFlattensCityStandardDepartments() throws Exception {
        when(directory.resolveServiceCityCode(COORDS)).thenReturn("410100");
        when(directory.standardDepartments("410100"))
                .thenReturn(List.of(
                        new PatientMedicalDirectoryService.StandardCategoryView(
                                "内科",
                                List.of(
                                        new PatientMedicalDirectoryService.StandardDepartmentItem(1L, "心血管内科"),
                                        new PatientMedicalDirectoryService.StandardDepartmentItem(2L, "呼吸内科"))),
                        new PatientMedicalDirectoryService.StandardCategoryView(
                                "外科", List.of(new PatientMedicalDirectoryService.StandardDepartmentItem(3L, "普外科")))));

        mvc().perform(get("/api/agent/standard-departments")
                        .param("longitude", "113.6458")
                        .param("latitude", "34.7572")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(3))
                .andExpect(jsonPath("$.departments[0].id").value(1))
                .andExpect(jsonPath("$.departments[0].name").value("心血管内科"))
                .andExpect(jsonPath("$.departments[0].category").value("内科"))
                .andExpect(jsonPath("$.departments[2].category").value("外科"));
        // 城市由服务端按坐标解析，Agent 不传 city_code
        verify(directory).resolveServiceCityCode(COORDS);
        verify(directory).standardDepartments("410100");
    }

    @Test
    void catalogWithoutCoordinatesFallsBackToFirstServiceCity() throws Exception {
        // 缺省首城：无坐标时解析逻辑回退服务城市列表首项
        when(directory.resolveServiceCityCode(null)).thenReturn("410100");
        when(directory.standardDepartments("410100")).thenReturn(List.of());

        mvc().perform(get("/api/agent/standard-departments").header(AgentCallbackAuthFilter.HEADER_NAME, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(0));
        verify(directory).resolveServiceCityCode(null);
    }

    @Test
    void slotsResolveCityOnServerAndCarryCampusAddressAndEarliestBookable() throws Exception {
        when(directory.resolveServiceCityCode(COORDS)).thenReturn("410100");
        PatientMedicalDirectoryService.DoctorSlotCard card = new PatientMedicalDirectoryService.DoctorSlotCard(
                1L,
                "林知远",
                "主任医师",
                "擅长腹腔镜微创手术",
                new BigDecimal("50.00"),
                1L,
                "郑州智愈综合医院",
                11L,
                "主院区",
                "郑州市金水区健康路 88 号",
                1.2,
                true,
                new PatientMedicalDirectoryService.EarliestSlot("2026-08-07", "上午"),
                List.of(new PatientMedicalDirectoryService.SlotItem(9L, "2026-08-07", "上午", 5)));
        when(directory.standardDepartmentSlots(3L, "410100", COORDS, null))
                .thenReturn(new PatientMedicalDirectoryService.StandardDepartmentSlotsView(
                        new PatientMedicalDirectoryService.StandardDepartmentInfo(3L, "普外科", "外科"),
                        List.of("2026-08-07"),
                        List.of(card)));

        // 跨医院查询只使用标准科室 ID（路径参数生效）
        mvc().perform(get("/api/agent/standard-departments/3/slots")
                        .param("longitude", "113.6458")
                        .param("latitude", "34.7572")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standard_department.id").value(3))
                .andExpect(jsonPath("$.doctors[0].campus_address").value("郑州市金水区健康路 88 号"))
                .andExpect(jsonPath("$.doctors[0].earliest_bookable.date").value("2026-08-07"))
                .andExpect(jsonPath("$.doctors[0].earliest_bookable.time_slot").value("上午"))
                .andExpect(jsonPath("$.doctors[0].bookable").value(true));
        verify(directory).standardDepartmentSlots(3L, "410100", COORDS, null);
    }

    @Test
    void slotsWithoutCoordinatesUseFirstServiceCity() throws Exception {
        when(directory.resolveServiceCityCode(null)).thenReturn("410100");
        when(directory.standardDepartmentSlots(3L, "410100", null, null))
                .thenReturn(new PatientMedicalDirectoryService.StandardDepartmentSlotsView(
                        new PatientMedicalDirectoryService.StandardDepartmentInfo(3L, "普外科", "外科"),
                        List.of("2026-08-07"),
                        List.of()));

        mvc().perform(get("/api/agent/standard-departments/3/slots")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctors.length()").value(0));
        verify(directory).standardDepartmentSlots(3L, "410100", null, null);
    }

    @Test
    void unknownStandardDepartmentReturns404() throws Exception {
        when(directory.resolveServiceCityCode(null)).thenReturn("410100");
        when(directory.standardDepartmentSlots(99L, "410100", null, null)).thenThrow(new ApiException(404, "标准科室不存在"));

        mvc().perform(get("/api/agent/standard-departments/99/slots")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("标准科室不存在"));
    }

    @Test
    void incompleteCoordinatesAreRejected() throws Exception {
        // 与 C 端目录同一规则：lat/lng 必须成对出现
        mvc().perform(get("/api/agent/standard-departments")
                        .param("longitude", "113.6458")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, SECRET))
                .andExpect(status().isBadRequest());
        verify(directory, org.mockito.Mockito.never()).resolveServiceCityCode(org.mockito.ArgumentMatchers.any());
    }
}

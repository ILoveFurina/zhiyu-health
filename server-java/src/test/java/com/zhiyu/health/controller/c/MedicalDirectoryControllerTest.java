package com.zhiyu.health.controller.c;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.service.PatientMedicalDirectoryService;
import com.zhiyu.health.service.PatientMedicalDirectoryService.Coordinates;
import com.zhiyu.health.support.StaffTokens;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** C 端确定性医疗目录 HTTP seam（票 49）：城市硬过滤、坐标成对校验、患者令牌保护。 */
@WebMvcTest(MedicalDirectoryController.class)
class MedicalDirectoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientMedicalDirectoryService directory;

    private static final Coordinates COORDS = new Coordinates(34.7572, 113.6458);

    @Test
    void serviceCitiesAreAggregatedDynamically() throws Exception {
        when(directory.serviceCities(COORDS))
                .thenReturn(List.of(new PatientMedicalDirectoryService.CityView("410100", "郑州市")));

        mockMvc.perform(get("/api/c/service-cities")
                        .param("lat", "34.7572")
                        .param("lng", "113.6458")
                        .with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].city_code").value("410100"))
                .andExpect(jsonPath("$[0].city_name").value("郑州市"));
        verify(directory).serviceCities(COORDS);
    }

    @Test
    void hospitalsAreHardFilteredByCityAndCarryNearestCampus() throws Exception {
        when(directory.hospitals("410100", COORDS))
                .thenReturn(List.of(
                        new PatientMedicalDirectoryService.HospitalView(1L, "郑州智愈综合医院", "三级甲等", 11L, "主院区", 1.2)));
        when(directory.hospitals("999999", COORDS)).thenReturn(List.of());

        mockMvc.perform(get("/api/c/hospitals")
                        .param("city_code", "410100")
                        .param("lat", "34.7572")
                        .param("lng", "113.6458")
                        .with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].hospital_id").value(1))
                .andExpect(jsonPath("$[0].campus_id").value(11))
                .andExpect(jsonPath("$[0].campus_name").value("主院区"))
                .andExpect(jsonPath("$[0].distance_km").value(1.2));
        // 无本院区数据的城市返回空列表，不跨城市推荐
        mockMvc.perform(get("/api/c/hospitals")
                        .param("city_code", "999999")
                        .param("lat", "34.7572")
                        .param("lng", "113.6458")
                        .with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void hospitalsRequireCityCode() throws Exception {
        mockMvc.perform(get("/api/c/hospitals").with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hospitalsWithoutCoordinatesKeepNullDistance() throws Exception {
        when(directory.hospitals("410100", null))
                .thenReturn(List.of(
                        new PatientMedicalDirectoryService.HospitalView(1L, "郑州智愈综合医院", "三级甲等", 11L, "主院区", null)));

        mockMvc.perform(get("/api/c/hospitals").param("city_code", "410100").with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].distance_km").doesNotExist());
        verify(directory).hospitals("410100", null);
    }

    @Test
    void rejectsIncompleteCoordinates() throws Exception {
        mockMvc.perform(get("/api/c/hospitals")
                        .param("city_code", "410100")
                        .param("lat", "34.7572")
                        .with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void campusesOfHospitalAreSortedByDistance() throws Exception {
        when(directory.campuses(1L, COORDS))
                .thenReturn(List.of(
                        new PatientMedicalDirectoryService.CampusView(11L, "主院区", "郑州市金水区健康路 88 号", 1.2),
                        new PatientMedicalDirectoryService.CampusView(12L, "郑东院区", "郑州市郑东新区龙湖中路 66 号", 8.9)));

        mockMvc.perform(get("/api/c/hospitals/1/campuses")
                        .param("lat", "34.7572")
                        .param("lng", "113.6458")
                        .with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].campus_id").value(11))
                .andExpect(jsonPath("$[0].address").value("郑州市金水区健康路 88 号"))
                .andExpect(jsonPath("$[1].campus_id").value(12));
    }

    @Test
    void campusDepartmentsCarryCategoryName() throws Exception {
        when(directory.campusDepartments(11L))
                .thenReturn(List.of(new PatientMedicalDirectoryService.CampusDepartmentView(
                        1L, "心血管内科", "内科", "门诊楼 3 层", "东区 301 室")));

        mockMvc.perform(get("/api/c/campuses/11/departments").with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].department_id").value(1))
                .andExpect(jsonPath("$[0].category_name").value("内科"));
    }

    @Test
    void standardDepartmentsAreGroupedByCategoryForCity() throws Exception {
        when(directory.standardDepartments("410100"))
                .thenReturn(List.of(new PatientMedicalDirectoryService.StandardCategoryView(
                        "内科", List.of(new PatientMedicalDirectoryService.StandardDepartmentItem(1L, "心血管内科")))));

        mockMvc.perform(get("/api/c/standard-departments")
                        .param("city_code", "410100")
                        .with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("内科"))
                .andExpect(jsonPath("$[0].departments[0].id").value(1))
                .andExpect(jsonPath("$[0].departments[0].name").value("心血管内科"));
    }

    @Test
    void standardDepartmentSlotsReturnCardPayload() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 6);
        PatientMedicalDirectoryService.DoctorSlotCard card = new PatientMedicalDirectoryService.DoctorSlotCard(
                1L,
                "林知远",
                "主任医师",
                new BigDecimal("50.00"),
                1L,
                "郑州智愈综合医院",
                11L,
                "主院区",
                1.2,
                true,
                null,
                List.of(new PatientMedicalDirectoryService.SlotItem(1L, "2026-08-06", "上午", 10)));
        when(directory.standardDepartmentSlots(1L, "410100", COORDS, date))
                .thenReturn(new PatientMedicalDirectoryService.StandardDepartmentSlotsView(
                        new PatientMedicalDirectoryService.StandardDepartmentInfo(1L, "心血管内科", "内科"),
                        List.of("2026-08-06"),
                        List.of(card)));

        mockMvc.perform(get("/api/c/standard-departments/1/slots")
                        .param("city_code", "410100")
                        .param("lat", "34.7572")
                        .param("lng", "113.6458")
                        .param("date", "2026-08-06")
                        .with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standard_department.id").value(1))
                .andExpect(jsonPath("$.standard_department.category").value("内科"))
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.doctors[0].doctor_id").value(1))
                .andExpect(jsonPath("$.doctors[0].registration_fee").value(50.00))
                .andExpect(jsonPath("$.doctors[0].campus_name").value("主院区"))
                .andExpect(jsonPath("$.doctors[0].bookable").value(true))
                .andExpect(jsonPath("$.doctors[0].slots[0].time_slot").value("上午"))
                .andExpect(jsonPath("$.doctors[0].slots[0].remaining_slots").value(10));
    }

    @Test
    void standardDepartmentSlotsRejectUnknownStandardDepartment() throws Exception {
        when(directory.standardDepartmentSlots(99L, "410100", null, null)).thenThrow(new ApiException(404, "标准科室不存在"));

        mockMvc.perform(get("/api/c/standard-departments/99/slots")
                        .param("city_code", "410100")
                        .with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("标准科室不存在"));
    }

    @Test
    void standardDepartmentSlotsRejectMalformedDate() throws Exception {
        mockMvc.perform(get("/api/c/standard-departments/1/slots")
                        .param("city_code", "410100")
                        .param("date", "2026/08/06")
                        .with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void doctorsAndSchedulesKeepWorking() throws Exception {
        when(directory.doctors(2L))
                .thenReturn(List.of(new PatientMedicalDirectoryService.DoctorView(
                        3L, 2L, "周安宁", "副主任医师", new BigDecimal("30.00"), "冠心病", "/doctor.png")));
        when(directory.schedules(3L))
                .thenReturn(
                        List.of(new PatientMedicalDirectoryService.ScheduleView(4L, 3L, "2026-08-03", "上午", 10, 2)));

        mockMvc.perform(get("/api/c/departments/2/doctors").with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].registration_fee").value(30.00));
        mockMvc.perform(get("/api/c/doctors/3/schedules").with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].remaining_slots").value(2));
    }

    @Test
    void everyBrowseEndpointRejectsMissingPatientToken() throws Exception {
        mockMvc.perform(get("/api/c/service-cities")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/c/hospitals").param("city_code", "410100")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/c/hospitals/1/campuses")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/c/campuses/11/departments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/c/standard-departments").param("city_code", "410100"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/c/standard-departments/1/slots").param("city_code", "410100"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/c/departments/2/doctors")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/c/doctors/3/schedules")).andExpect(status().isUnauthorized());
    }
}

package com.zhiyu.health.controller.c;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.service.PatientMedicalDirectoryService;
import com.zhiyu.health.support.StaffTokens;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** C 端医疗目录 HTTP seam：逐级浏览且统一受患者令牌保护。 */
@WebMvcTest(MedicalDirectoryController.class)
class MedicalDirectoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientMedicalDirectoryService directory;

    @Test
    void browsesHospitalsDepartmentsDoctorsAndFutureSchedules() throws Exception {
        when(directory.hospitals(31.2304, 121.4737))
                .thenReturn(List.of(new PatientMedicalDirectoryService.HospitalView(
                        1L, "智愈市人民医院", "三级甲等", "智愈市安康路 88 号", 121.4737, 31.2304, 0.0)));
        when(directory.departments(1L))
                .thenReturn(List.of(new PatientMedicalDirectoryService.DepartmentView(2L, 1L, "心血管内科", "3层", "A区")));
        when(directory.doctors(2L))
                .thenReturn(List.of(new PatientMedicalDirectoryService.DoctorView(
                        3L, 2L, "周安宁", "副主任医师", new BigDecimal("30.00"), "冠心病", "/doctor.png")));
        when(directory.schedules(3L))
                .thenReturn(
                        List.of(new PatientMedicalDirectoryService.ScheduleView(4L, 3L, "2026-08-03", "上午", 10, 2)));

        mockMvc.perform(get("/api/c/hospitals")
                        .param("lat", "31.2304")
                        .param("lng", "121.4737")
                        .with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hospital_id").value(1))
                .andExpect(jsonPath("$[0].longitude").value(121.4737))
                .andExpect(jsonPath("$[0].latitude").value(31.2304))
                .andExpect(jsonPath("$[0].distance_km").value(0.0));
        mockMvc.perform(get("/api/c/hospitals/1/departments").with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].department_id").value(2));
        mockMvc.perform(get("/api/c/departments/2/doctors").with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].registration_fee").value(30.00));
        mockMvc.perform(get("/api/c/doctors/3/schedules").with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].remaining_slots").value(2));

        verify(directory).hospitals(31.2304, 121.4737);
        verify(directory).departments(1L);
        verify(directory).doctors(2L);
        verify(directory).schedules(3L);
    }

    @Test
    void everyBrowseEndpointRejectsMissingPatientToken() throws Exception {
        mockMvc.perform(get("/api/c/hospitals")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/c/hospitals/1/departments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/c/departments/2/doctors")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/c/doctors/3/schedules")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsAllHospitalsWhenCoordinatesAreOmitted() throws Exception {
        when(directory.hospitals(null, null))
                .thenReturn(List.of(new PatientMedicalDirectoryService.HospitalView(
                        1L, "智愈市人民医院", "三级甲等", "智愈市安康路 88 号", 121.4737, 31.2304, null)));

        mockMvc.perform(get("/api/c/hospitals").with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hospital_id").value(1))
                .andExpect(jsonPath("$[0].distance_km").doesNotExist());
        verify(directory).hospitals(null, null);
    }
}

package com.zhiyu.health.controller.c;

import com.zhiyu.health.service.PatientMedicalDirectoryService;
import com.zhiyu.health.service.PatientMedicalDirectoryService.Coordinates;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C 端医疗目录入口：只校验路径/坐标并委托只读 service。 */
@Validated
@RestController
@RequestMapping("/api/c")
@RequiredArgsConstructor
public class MedicalDirectoryController {

    private final PatientMedicalDirectoryService directory;

    @GetMapping("/hospitals")
    public List<PatientMedicalDirectoryService.HospitalView> hospitals(
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng) {
        return directory.hospitals(Coordinates.fromNullable(lat, lng));
    }

    @GetMapping("/hospitals/{hospitalId}/departments")
    public List<PatientMedicalDirectoryService.DepartmentView> departments(@PathVariable @Positive long hospitalId) {
        return directory.departments(hospitalId);
    }

    @GetMapping("/departments/{departmentId}/doctors")
    public List<PatientMedicalDirectoryService.DoctorView> doctors(@PathVariable @Positive long departmentId) {
        return directory.doctors(departmentId);
    }

    @GetMapping("/doctors/{doctorId}/schedules")
    public List<PatientMedicalDirectoryService.ScheduleView> schedules(@PathVariable @Positive long doctorId) {
        return directory.schedules(doctorId);
    }
}

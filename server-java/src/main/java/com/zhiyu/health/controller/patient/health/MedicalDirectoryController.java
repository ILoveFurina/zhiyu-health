package com.zhiyu.health.controller.patient.health;

import com.zhiyu.health.service.health.PatientMedicalDirectoryService;
import com.zhiyu.health.service.health.PatientMedicalDirectoryService.Coordinates;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C 端确定性医疗目录入口（票 49）：只校验路径/参数并委托只读 service。 */
@Validated
@RestController
@RequestMapping("/api/c")
@RequiredArgsConstructor
public class MedicalDirectoryController {

    private final PatientMedicalDirectoryService directory;

    @GetMapping("/service-cities")
    public List<PatientMedicalDirectoryService.CityView> serviceCities(
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng) {
        return directory.serviceCities(Coordinates.fromNullable(lat, lng));
    }

    @GetMapping("/hospitals")
    public List<PatientMedicalDirectoryService.HospitalView> hospitals(
            @RequestParam("city_code") @NotBlank String cityCode,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng) {
        return directory.hospitals(cityCode, Coordinates.fromNullable(lat, lng));
    }

    @GetMapping("/hospitals/{hospitalId}/campuses")
    public List<PatientMedicalDirectoryService.CampusView> campuses(
            @PathVariable @Positive long hospitalId,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng) {
        return directory.campuses(hospitalId, Coordinates.fromNullable(lat, lng));
    }

    @GetMapping("/campuses/{campusId}/departments")
    public List<PatientMedicalDirectoryService.CampusDepartmentView> campusDepartments(
            @PathVariable @Positive long campusId) {
        return directory.campusDepartments(campusId);
    }

    @GetMapping("/standard-departments")
    public List<PatientMedicalDirectoryService.StandardCategoryView> standardDepartments(
            @RequestParam("city_code") @NotBlank String cityCode) {
        return directory.standardDepartments(cityCode);
    }

    @GetMapping("/standard-departments/{standardDepartmentId}/slots")
    public PatientMedicalDirectoryService.StandardDepartmentSlotsView standardDepartmentSlots(
            @PathVariable @Positive long standardDepartmentId,
            @RequestParam("city_code") @NotBlank String cityCode,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return directory.standardDepartmentSlots(
                standardDepartmentId, cityCode, Coordinates.fromNullable(lat, lng), date);
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

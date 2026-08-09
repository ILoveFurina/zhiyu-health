package com.zhiyu.health.controller.agent;

import com.zhiyu.health.service.health.PatientMedicalDirectoryService;
import com.zhiyu.health.service.health.PatientMedicalDirectoryService.Coordinates;
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

/**
 * 智能导诊标准科室工具回调（票 50）：server-py 编排层在科室明确后确定性查询标准科室目录与号源。
 * 与 C 端目录的差异：不传 city_code，由服务端按可选坐标解析当前服务城市（无坐标取服务城市首项）；
 * 跨医院查询只使用标准科室 ID。
 */
@Validated
@RestController
@RequestMapping("/api/agent/standard-departments")
@RequiredArgsConstructor
public class StandardDepartmentToolController {

    private final PatientMedicalDirectoryService directory;

    @GetMapping
    public StandardDepartmentCatalog catalog(
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double latitude) {
        String cityCode = directory.resolveServiceCityCode(Coordinates.fromNullable(latitude, longitude));
        return new StandardDepartmentCatalog(directory.standardDepartments(cityCode).stream()
                .flatMap(category -> category.departments().stream()
                        .map(department ->
                                new StandardDepartmentEntry(department.id(), department.name(), category.category())))
                .toList());
    }

    @GetMapping("/{standardDepartmentId}/slots")
    public PatientMedicalDirectoryService.StandardDepartmentSlotsView slots(
            @PathVariable @Positive long standardDepartmentId,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double latitude) {
        Coordinates coordinates = Coordinates.fromNullable(latitude, longitude);
        return directory.standardDepartmentSlots(
                standardDepartmentId, directory.resolveServiceCityCode(coordinates), coordinates, null);
    }

    public record StandardDepartmentCatalog(List<StandardDepartmentEntry> departments) {}

    public record StandardDepartmentEntry(long id, String name, String category) {}
}

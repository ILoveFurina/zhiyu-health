package com.zhiyu.health.controller.staff.organization;

import com.zhiyu.health.controller.staff.organization.mapping.StandardDepartmentInputMapper;
import com.zhiyu.health.entity.organization.StandardDepartment;
import com.zhiyu.health.service.organization.StandardDepartmentAdminService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 平台标准科室管理（票 49）：仅 admin 角色可操作（AdminInterceptor），业务在 StandardDepartmentAdminService */
@RestController
@RequestMapping("/api/b/standard-departments")
@RequiredArgsConstructor
public class StandardDepartmentController {

    private final StandardDepartmentAdminService standardDepartmentAdminService;
    private final StandardDepartmentInputMapper standardDepartmentInputMapper;

    public record StandardDepartmentInput(
            @NotBlank @Size(max = 50) String category,
            @NotBlank @Size(max = 100) String name,
            @NotNull Integer sortOrder) {}

    @GetMapping
    public List<StandardDepartment> list() {
        return standardDepartmentAdminService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StandardDepartment create(@Validated @RequestBody StandardDepartmentInput input) {
        return standardDepartmentAdminService.create(standardDepartmentInputMapper.toEntity(input));
    }

    @PutMapping("/{id}")
    public StandardDepartment update(@PathVariable long id, @Validated @RequestBody StandardDepartmentInput input) {
        StandardDepartment standardDepartment = standardDepartmentInputMapper.toEntity(input);
        standardDepartment.setId(id);
        return standardDepartmentAdminService.update(standardDepartment);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        standardDepartmentAdminService.delete(id);
    }
}

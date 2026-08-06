package com.zhiyu.health.controller.b;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.controller.b.mapping.PrescriptionTemplateInputMapper;
import com.zhiyu.health.service.PrescriptionTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 处方模板（票 47）：医生区端点（/api/b/reception/** 已被 AdminInterceptor 排除），归属校验在 service。 */
@RestController
@RequestMapping("/api/b/reception/prescription-templates")
@RequiredArgsConstructor
public class PrescriptionTemplateController {

    private final PrescriptionTemplateService service;
    private final PrescriptionTemplateInputMapper inputMapper;

    public record ItemInput(
            @JsonProperty("medication_id") long medicationId,
            @NotBlank @Size(max = 100) String dosage,
            @NotBlank @Size(max = 100) String frequency,
            @NotBlank @Size(max = 100) String duration,
            @Size(max = 500) String notes) {}

    public record TemplateInput(
            @NotBlank @Size(max = 100) String name, @NotEmpty @Size(max = 20) List<@Valid ItemInput> items) {}

    @GetMapping
    public List<PrescriptionTemplateService.TemplateView> list(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return service.listTemplates(staffId);
    }

    @GetMapping("/{id}")
    public PrescriptionTemplateService.TemplateView detail(
            @PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return service.getDetail(staffId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PrescriptionTemplateService.TemplateView create(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId, @Valid @RequestBody TemplateInput input) {
        return service.create(inputMapper.toSaveCommand(staffId, input));
    }

    @PutMapping("/{id}")
    public PrescriptionTemplateService.TemplateView update(
            @PathVariable long id,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId,
            @Valid @RequestBody TemplateInput input) {
        return service.update(staffId, id, inputMapper.toSaveCommand(staffId, input));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        service.delete(staffId, id);
    }
}

package com.zhiyu.health.controller.patient.health;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.controller.patient.health.mapping.HealthObservationInputMapper;
import com.zhiyu.health.service.health.HealthObservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端健康观测核验入口（票 61，ADR-0031）：只做校验、患者身份装配和 DTO 映射。 */
@RestController
@RequestMapping("/api/c/health-observations")
@RequiredArgsConstructor
public class HealthObservationController {

    private final HealthObservationService service;
    private final HealthObservationInputMapper inputMapper;

    /** 纠错请求体：只接受新值字符串；日期、指标、单位、来源报告一律不可改。 */
    public record CorrectInput(@NotBlank @Size(max = 50) String value) {}

    @PostMapping("/{id}/confirm")
    public HealthObservationService.ObservationView confirm(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long id) {
        return service.confirm(patientId, id);
    }

    @PostMapping("/{id}/correct")
    public HealthObservationService.ObservationView correct(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @PathVariable long id,
            @Valid @RequestBody CorrectInput input) {
        return service.correct(inputMapper.toCommand(patientId, id, input));
    }

    @PostMapping("/{id}/reject")
    public HealthObservationService.ObservationView reject(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long id) {
        return service.reject(patientId, id);
    }
}

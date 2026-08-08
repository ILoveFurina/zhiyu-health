package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.ReceptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 医生接诊台：doctor 角色由 ReceptionService 在业务层校验，controller 只装配员工身份 */
@RestController
@RequestMapping("/api/b/reception")
@RequiredArgsConstructor
public class ReceptionController {

    private final ReceptionService receptionService;

    public record CompleteInput(
            @NotBlank @Size(max = 2000) String diagnosis, @NotBlank @Size(max = 2000) String advice) {}

    @GetMapping
    public ReceptionService.ReceptionDashboard today(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return receptionService.today(staffId);
    }

    @GetMapping("/appointments/{id}")
    public ReceptionService.AppointmentDetail detail(
            @PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return receptionService.detail(staffId, id);
    }

    @PostMapping("/appointments/{id}/complete")
    public ReceptionService.AppointmentDetail complete(
            @PathVariable long id,
            @Valid @RequestBody CompleteInput input,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return receptionService.complete(staffId, id, input.diagnosis(), input.advice());
    }

    @PostMapping("/appointments/{id}/call")
    public ReceptionService.AppointmentDetail call(
            @PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return receptionService.call(staffId, id);
    }
}

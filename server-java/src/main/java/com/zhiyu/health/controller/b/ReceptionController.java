package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.ReceptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/reception")
public class ReceptionController {

    private final ReceptionService receptionService;

    public ReceptionController(ReceptionService receptionService) {
        this.receptionService = receptionService;
    }

    public record CompleteInput(
            @NotBlank @Size(max = 2000) String diagnosis,
            @NotBlank @Size(max = 2000) String advice) { }

    @GetMapping
    public ReceptionService.ReceptionDashboard today(HttpServletRequest request) {
        return receptionService.today(staffId(request));
    }

    @GetMapping("/appointments/{id}")
    public ReceptionService.AppointmentDetail detail(@PathVariable long id,
                                                     HttpServletRequest request) {
        return receptionService.detail(staffId(request), id);
    }

    @PostMapping("/appointments/{id}/complete")
    public ReceptionService.AppointmentDetail complete(
            @PathVariable long id, @Valid @RequestBody CompleteInput input,
            HttpServletRequest request) {
        return receptionService.complete(staffId(request), id, input.diagnosis(), input.advice());
    }

    private long staffId(HttpServletRequest request) {
        return Long.parseLong((String) request.getAttribute(AuthFilter.ATTR_AUTH_SUBJECT));
    }
}

package com.zhiyu.health.controller.patient.consultation;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.consultation.PatientCareService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/c")
@RequiredArgsConstructor
public class PatientCareController {
    private final PatientCareService service;

    @GetMapping("/prescriptions")
    public List<PatientCareService.PatientPrescriptionView> prescriptions(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return service.prescriptions(patientId);
    }

    @GetMapping("/messages")
    public List<PatientCareService.MessageView> messages(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return service.messages(patientId);
    }
}

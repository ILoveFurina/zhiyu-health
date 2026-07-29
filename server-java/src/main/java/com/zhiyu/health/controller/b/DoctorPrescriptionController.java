package com.zhiyu.health.controller.b;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.controller.b.mapping.PrescriptionInputMapper;
import com.zhiyu.health.service.PrescriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/reception")
@RequiredArgsConstructor
public class DoctorPrescriptionController {
    private final PrescriptionService service;
    private final PrescriptionInputMapper inputMapper;

    public record ItemInput(
            @JsonProperty("medication_id") long medicationId,
            @NotBlank @Size(max = 100) String dosage,
            @NotBlank @Size(max = 100) String frequency,
            @NotBlank @Size(max = 100) String duration,
            @Size(max = 500) String notes) {}

    public record CreateInput(@Size(max = 1000) String notes, @NotEmpty @Size(max = 20) List<@Valid ItemInput> items) {}

    @GetMapping("/medications")
    public List<PrescriptionService.MedicationView> medications(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return service.listMedications(staffId);
    }

    @PostMapping("/appointments/{appointmentId}/prescriptions")
    public PrescriptionService.PrescriptionView create(
            @PathVariable long appointmentId,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId,
            @Valid @RequestBody CreateInput input) {
        return service.create(inputMapper.toCommand(staffId, appointmentId, input));
    }
}

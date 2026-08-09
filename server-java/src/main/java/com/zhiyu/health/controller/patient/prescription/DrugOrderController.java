package com.zhiyu.health.controller.patient.prescription;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.controller.patient.prescription.mapping.DrugOrderInputMapper;
import com.zhiyu.health.service.prescription.DrugOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
@RequestMapping("/api/c/drug-orders")
@RequiredArgsConstructor
public class DrugOrderController {
    private final DrugOrderService service;
    private final DrugOrderInputMapper inputMapper;

    public record ItemInput(
            @JsonProperty("medication_id") @NotNull @Positive Long medicationId, @NotNull @Min(1) Integer quantity) {}

    public record CreateInput(
            @JsonProperty("prescription_id") @Positive Long prescriptionId, List<@Valid ItemInput> items) {}

    @GetMapping
    public List<DrugOrderService.OrderView> list(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return service.listForPatient(patientId);
    }

    @PostMapping
    public DrugOrderService.OrderView create(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @Valid @RequestBody CreateInput input) {
        return service.create(inputMapper.toCommand(patientId, input));
    }

    @PostMapping("/{id}/cancel")
    public DrugOrderService.OrderView cancel(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long id) {
        return service.cancel(patientId, id);
    }

    @PostMapping("/{id}/pay")
    public DrugOrderService.OrderView pay(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long id) {
        return service.pay(patientId, id);
    }
}

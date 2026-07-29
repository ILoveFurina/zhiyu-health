package com.zhiyu.health.controller.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.controller.agent.mapping.ContraindicationDtoMapper;
import com.zhiyu.health.service.ContraindicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 禁忌工具回调：模型只提供候选药，患者上下文由可信运行时注入。 */
@RestController
@RequestMapping("/api/agent/contraindications")
@RequiredArgsConstructor
public class ContraindicationController {

    private final ContraindicationService service;
    private final ContraindicationDtoMapper dtoMapper;

    @PostMapping("/check")
    public CheckResponse check(@Valid @RequestBody CheckRequest request) {
        return dtoMapper.toResponse(service.check(dtoMapper.toCommand(request)));
    }

    public record CheckRequest(
            @JsonProperty("patient_id") @NotNull @Positive Long patientId,
            @JsonProperty("medication_ids") @NotEmpty @Size(max = 20) List<@NotNull @Positive Long> medicationIds) {}

    public record CheckResponse(
            String decision,
            String messageType,
            boolean blocked,
            List<String> reasons,
            String message,
            String advice) {}
}

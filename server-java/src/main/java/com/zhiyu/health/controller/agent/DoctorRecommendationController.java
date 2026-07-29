package com.zhiyu.health.controller.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.service.DoctorRecommendationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** server-py 业务工具回调接口：只做参数校验与响应装配。 */
@Validated
@RestController
@RequestMapping("/api/agent/doctors")
@RequiredArgsConstructor
public class DoctorRecommendationController {

    private final DoctorRecommendationService recommendationService;

    @GetMapping("/recommend")
    public DoctorRecommendations recommend(@RequestParam("department_name") @NotBlank String departmentName) {
        return new DoctorRecommendations(recommendationService.recommendDoctors(departmentName));
    }

    @GetMapping("/{doctorId}/slots")
    public DoctorSlots slots(@PathVariable @Positive long doctorId) {
        return new DoctorSlots(doctorId, recommendationService.getDoctorSlots(doctorId));
    }

    public record DoctorRecommendations(List<DoctorRecommendationService.DoctorRecommendation> doctors) {}

    public record DoctorSlots(
            @JsonProperty("doctor_id") long doctorId, List<DoctorRecommendationService.DoctorSlot> slots) {}
}

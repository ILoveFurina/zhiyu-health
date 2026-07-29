package com.zhiyu.health.controller.agent;

import com.zhiyu.health.service.HospitalRecommendationService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** server-py 业务工具回调接口：只做参数校验与响应装配。 */
@Validated
@RestController
@RequestMapping("/api/agent/hospitals")
public class HospitalRecommendationController {

    private final HospitalRecommendationService recommendationService;

    public HospitalRecommendationController(HospitalRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping("/nearby")
    public HospitalRecommendations nearby(
            @RequestParam("longitude") @NotNull
            @DecimalMin("-180") @DecimalMax("180") double longitude,
            @RequestParam("latitude") @NotNull
            @DecimalMin("-90") @DecimalMax("90") double latitude) {
        return new HospitalRecommendations(recommendationService.recommendNearby(longitude, latitude));
    }

    public record HospitalRecommendations(
            List<HospitalRecommendationService.HospitalRecommendation> hospitals) {
    }
}

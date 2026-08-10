package com.zhiyu.health.controller.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.service.organization.DoctorRecommendationService;
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

/**
 * server-py 业务工具回调接口：只做参数校验与响应装配。
 * 这是 Agent 导诊链路的只读回调端点（server-py 在确定科室后回调此处取推荐医生与号源），
 * 非患者直连--鉴权走内网回调口径，不带 @RequestAttribute 患者/B 端身份，勿误判为漏鉴权。
 */
@Validated
@RestController
@RequestMapping("/api/agent/doctors")
@RequiredArgsConstructor
public class DoctorRecommendationController {

    private final DoctorRecommendationService recommendationService;

    // 导诊流程"科室确定后的医生选择步"：按科室名返回推荐医生列表供 Agent 下一步选号源。
    @GetMapping("/recommend")
    public DoctorRecommendations recommend(@RequestParam("department_name") @NotBlank String departmentName) {
        return new DoctorRecommendations(recommendationService.recommendDoctors(departmentName));
    }

    // 导诊流程"选定医生后的号源展示步"：返回该医生可挂号源供 Agent 推进挂号。
    @GetMapping("/{doctorId}/slots")
    public DoctorSlots slots(@PathVariable @Positive long doctorId) {
        return new DoctorSlots(doctorId, recommendationService.getDoctorSlots(doctorId));
    }

    public record DoctorRecommendations(List<DoctorRecommendationService.DoctorRecommendation> doctors) {}

    public record DoctorSlots(
            @JsonProperty("doctor_id") long doctorId, List<DoctorRecommendationService.DoctorSlot> slots) {}
}

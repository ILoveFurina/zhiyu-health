package com.zhiyu.health.controller.staff.demo;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.demo.DemoDashboardService;
import com.zhiyu.health.service.demo.DemoDashboardService.DashboardView;
import com.zhiyu.health.service.demo.DemoKnowledgeSourceService;
import com.zhiyu.health.service.demo.DemoResetService;
import com.zhiyu.health.service.demo.DemoResetService.ResetResult;
import com.zhiyu.health.service.demo.DemoTimeSlotService;
import com.zhiyu.health.service.demo.DemoTimeSlotService.TimeSlotWindowView;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示武器包入口（票 25，ADR-0022）：全部收口在 {@code /api/b/demo/**}，仅 admin 可用（/api/b/** 路由级角色授权）。
 *
 * 演示重置三重保护在 {@link DemoResetService} 内执行；中途失败保持冻结、返回步骤清单，
 * 接口幂等可从失败步重跑，不自动回滚。成功 200 / 失败 503，体形状统一为 {@link ResetResult}。
 *
 * 票 88（ADR-0035）：Mock 药店库存同步两枚端点已随「平台中心药房/Mock 外部药店」模型移除，
 * 库存真实归属各院区药房（/api/b/campus-pharmacies/**），不再保留误导性外部药店入口。
 *
 * 演示时段设置（票 87）：GET/PUT {@code /time-slot-windows} 覆盖上午/下午起止，
 * 受 {@code DEMO_TIME_SLOT_ENABLED} 门控，env 关闭时整个能力 403 不可用。
 */
@RestController
@RequestMapping("/api/b/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoResetService resetService;
    private final DemoDashboardService dashboardService;
    private final DemoKnowledgeSourceService knowledgeSourceService;
    private final DemoTimeSlotService timeSlotService;

    public record ResetRequest(@NotBlank String confirm) {}

    public record KnowledgeSourceRequest(String knowledgeSource) {}

    public record KnowledgeSourceView(String knowledgeSource) {}

    public record TimeSlotWindowRequest(Map<String, Contracts.ScheduleRequestFlow.TimeSlotWindow> timeSlotWindows) {}

    @PostMapping("/reset")
    public ResponseEntity<ResetResult> reset(@RequestBody ResetRequest request) {
        ResetResult result = resetService.reset(request.confirm());
        // 成功 200；中途失败 503（保持冻结，演示者可据 pendingSteps 重跑）
        return ResponseEntity.status(result.success() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(result);
    }

    /** 演示看板：单接口返回聚合四类指标，数据经 server-java 实时读取。 */
    @GetMapping("/dashboard")
    public DashboardView dashboard() {
        return dashboardService.dashboard();
    }

    /** 读知识源现场切换全局键；缺失返回默认 none。 */
    @GetMapping("/knowledge-source")
    public KnowledgeSourceView getKnowledgeSource() {
        return new KnowledgeSourceView(knowledgeSourceService.current());
    }

    /** 写知识源现场切换全局键（ADR-0021）；非法值由 service 抛 400。 */
    @PutMapping("/knowledge-source")
    public KnowledgeSourceView putKnowledgeSource(@RequestBody KnowledgeSourceRequest request) {
        knowledgeSourceService.update(request.knowledgeSource());
        return new KnowledgeSourceView(knowledgeSourceService.current());
    }

    /** 读演示时段覆盖（有效时段窗口）；env 未开启 403。 */
    @GetMapping("/time-slot-windows")
    public TimeSlotWindowView getTimeSlotWindows() {
        return timeSlotService.current();
    }

    /** 写演示时段覆盖；非法窗口 400。 */
    @PutMapping("/time-slot-windows")
    public TimeSlotWindowView putTimeSlotWindows(@RequestBody TimeSlotWindowRequest request) {
        return timeSlotService.update(request.timeSlotWindows());
    }
}

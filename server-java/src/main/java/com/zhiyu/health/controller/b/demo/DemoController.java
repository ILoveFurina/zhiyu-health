package com.zhiyu.health.controller.b.demo;

import com.zhiyu.health.service.DemoDashboardService;
import com.zhiyu.health.service.DemoDashboardService.DashboardView;
import com.zhiyu.health.service.DemoKnowledgeSourceService;
import com.zhiyu.health.service.DemoPharmacySyncService;
import com.zhiyu.health.service.DemoPharmacySyncService.PharmacyStockView;
import com.zhiyu.health.service.DemoPharmacySyncService.SyncResult;
import com.zhiyu.health.service.DemoResetService;
import com.zhiyu.health.service.DemoResetService.ResetResult;
import jakarta.validation.constraints.NotBlank;
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
 * 演示武器包入口（票 25，ADR-0022）：全部收口在 {@code /api/b/demo/**}，仅 admin 可用（AdminInterceptor）。
 *
 * 演示重置三重保护在 {@link DemoResetService} 内执行；中途失败保持冻结、返回步骤清单，
 * 接口幂等可从失败步重跑，不自动回滚。成功 200 / 失败 503，体形状统一为 {@link ResetResult}。
 *
 * Mock 药店库存同步（票 48，ADR-0026 演示展示层例外）两枚端点收口于此：
 * POST {@code /pharmacy-stock/sync} 触发同步，GET {@code /pharmacy-stock} 取库存快照。
 */
@RestController
@RequestMapping("/api/b/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoResetService resetService;
    private final DemoDashboardService dashboardService;
    private final DemoKnowledgeSourceService knowledgeSourceService;
    private final DemoPharmacySyncService pharmacySyncService;

    public record ResetRequest(@NotBlank String confirm) {}

    public record KnowledgeSourceRequest(String knowledgeSource) {}

    public record KnowledgeSourceView(String knowledgeSource) {}

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

    /** Mock 药店库存同步（票 48）：仅刷新进程内同步时间并返回统计，不触碰业务库存。 */
    @PostMapping("/pharmacy-stock/sync")
    public SyncResult syncPharmacyStock() {
        return pharmacySyncService.sync();
    }

    /** Mock 药店库存快照：虚构药店静态明细 + 上次同步时间（未同步为 null）。 */
    @GetMapping("/pharmacy-stock")
    public PharmacyStockView pharmacyStock() {
        return pharmacySyncService.snapshot();
    }
}

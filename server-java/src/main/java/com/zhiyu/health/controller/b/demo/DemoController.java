package com.zhiyu.health.controller.b.demo;

import com.zhiyu.health.service.DemoResetService;
import com.zhiyu.health.service.DemoResetService.ResetResult;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示武器包入口（票 25，ADR-0020）：全部收口在 {@code /api/b/demo/**}，仅 admin 可用（AdminInterceptor）。
 *
 * 演示重置三重保护在 {@link DemoResetService} 内执行；中途失败保持冻结、返回步骤清单，
 * 接口幂等可从失败步重跑，不自动回滚。成功 200 / 失败 503，体形状统一为 {@link ResetResult}。
 */
@RestController
@RequestMapping("/api/b/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoResetService resetService;

    public record ResetRequest(@NotBlank String confirm) {}

    @PostMapping("/reset")
    public ResponseEntity<ResetResult> reset(@RequestBody ResetRequest request) {
        ResetResult result = resetService.reset(request.confirm());
        // 成功 200；中途失败 503（保持冻结，演示者可据 pendingSteps 重跑）
        return ResponseEntity.status(result.success() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(result);
    }
}

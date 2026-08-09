package com.zhiyu.health.controller.common;

import com.zhiyu.health.service.common.HealthService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查：永远 200，状态体现在响应体 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return healthService.check();
    }
}

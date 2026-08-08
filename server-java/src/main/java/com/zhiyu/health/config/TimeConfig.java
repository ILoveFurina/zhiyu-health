package com.zhiyu.health.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源：集中提供 {@link Clock}，使号源时段截止等时间敏感判断可注入、可测试。
 *
 * <p>默认系统默认时区，与既有直接调用 {@code LocalDate.now()} 的语义一致（"今天"判定不变）。
 * 测试以固定 {@link Clock} 构造组件，避免依赖墙上时钟。
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}

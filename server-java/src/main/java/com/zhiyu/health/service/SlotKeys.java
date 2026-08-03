package com.zhiyu.health.service;

/**
 * 号源计数 Redis 键格式（单一事实源，避免 {@link RedisSlotCounter} 与 {@link DemoResetService} 重复）。
 *
 * 独立于 {@link SlotCounter} 层级：ArchUnit 限制 {@code SlotCounter} 只能被 {@link SlotAccounting}
 * 访问，故键格式常量不挂在 {@link RedisSlotCounter} 上，避免重置清键/断言触犯该规则。
 */
public final class SlotKeys {

    private SlotKeys() {}

    /** 指定排班的号源计数键。 */
    public static String key(long scheduleId) {
        return "schedule:" + scheduleId + ":remaining_slots";
    }

    /** 全部号源计数键的 SCAN 匹配模式（重置清键用）。 */
    public static String keyPattern() {
        return "schedule:*:remaining_slots";
    }
}

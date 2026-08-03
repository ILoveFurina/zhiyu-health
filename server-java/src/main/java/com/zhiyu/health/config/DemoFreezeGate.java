package com.zhiyu.health.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 演示重置冻结闸门（票 25，ADR-0020）：重置进行中冻结全部 C 端入口（{@code /api/c/**}）。
 *
 * 进程内 {@link AtomicBoolean} 与本地单实例拓扑匹配（ADR-0020 已否决 Redis 分布式锁）；
 * server-java 重启锁丢失即解冻，属可接受的人工步骤。B 端只读与重置接口本身不冻结，
 * 以便演示者从 B 端观察断言结果与重跑。
 */
@Component
public class DemoFreezeGate {

    private final AtomicBoolean frozen = new AtomicBoolean(false);

    /** 重置开始时冻结；已冻结返回 false（调用方据此判重复进入）。 */
    public boolean freeze() {
        return frozen.compareAndSet(false, true);
    }

    /** 重置完成后解冻。 */
    public void unfreeze() {
        frozen.set(false);
    }

    public boolean isFrozen() {
        return frozen.get();
    }
}

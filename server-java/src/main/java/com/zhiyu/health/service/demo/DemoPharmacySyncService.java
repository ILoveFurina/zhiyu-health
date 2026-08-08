package com.zhiyu.health.service.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Mock 药店库存同步（票 48，ADR-0026 修订的演示展示层例外）：向 B 端提供一份虚构合作药店
 * 库存快照，覆盖题目"药品库存同步（与药店数据打通）"的演示画面。
 *
 * 纯展示层：明细来自类路径静态 fixture（3 家虚构药店），同步动作只刷新进程内
 * {@code lastSyncedAt}（重启复位为"未同步"），全程不读写 medications 业务库存，
 * 不引入任何药店实体进业务模型。fixture 缺失或损坏属部署错误，启动期 fail-fast。
 */
@Service
public class DemoPharmacySyncService {

    private static final String FIXTURE_PATH = "demo/pharmacy-stock.json";

    private final List<PharmacyStock> pharmacies;
    private final AtomicReference<OffsetDateTime> lastSyncedAt = new AtomicReference<>();

    public DemoPharmacySyncService(ObjectMapper objectMapper) {
        this.pharmacies = load(objectMapper);
    }

    private static List<PharmacyStock> load(ObjectMapper objectMapper) {
        try {
            PharmacyStockFixture fixture = objectMapper.readValue(
                    new ClassPathResource(FIXTURE_PATH).getInputStream(), PharmacyStockFixture.class);
            return fixture.pharmacies();
        } catch (IOException e) {
            throw new IllegalStateException("Mock 药店库存 fixture 加载失败（检查 " + FIXTURE_PATH + " 是否随部署完整）", e);
        }
    }

    /** 触发同步：仅刷新进程内同步时间并返回统计，不触碰 medications 业务库存。 */
    public SyncResult sync() {
        OffsetDateTime syncedAt = OffsetDateTime.now();
        lastSyncedAt.set(syncedAt);
        int recordCount = pharmacies.stream()
                .mapToInt(pharmacy -> pharmacy.items().size())
                .sum();
        return new SyncResult(syncedAt, pharmacies.size(), recordCount);
    }

    /** 库存快照：静态 fixture 明细 + 进程内同步时间（未同步为 null）。 */
    public PharmacyStockView snapshot() {
        return new PharmacyStockView(lastSyncedAt.get(), pharmacies);
    }

    /** 同步结果（snake_case 经 Jackson 全局策略序列化）。 */
    public record SyncResult(OffsetDateTime syncedAt, int pharmacyCount, int recordCount) {}

    /** 库存快照视图；lastSyncedAt 为 null 表示尚未同步。 */
    public record PharmacyStockView(OffsetDateTime lastSyncedAt, List<PharmacyStock> pharmacies) {
        public PharmacyStockView {
            pharmacies = List.copyOf(pharmacies);
        }
    }

    public record PharmacyStock(String name, String region, List<PharmacyStockItem> items) {
        public PharmacyStock {
            items = List.copyOf(items);
        }
    }

    public record PharmacyStockItem(String medicationName, String specification, int stock) {}

    /** fixture 顶层包装；与 {@code demo/pharmacy-stock.json} 结构对应。 */
    public record PharmacyStockFixture(List<PharmacyStock> pharmacies) {}
}

package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.zhiyu.health.service.DemoPharmacySyncService.PharmacyStockView;
import com.zhiyu.health.service.DemoPharmacySyncService.SyncResult;
import org.junit.jupiter.api.Test;

/** Mock 药店库存同步（票 48）：fixture 加载、同步时间进程内流转（未同步 → 已同步）。 */
class DemoPharmacySyncServiceTest {

    private final DemoPharmacySyncService service = new DemoPharmacySyncService(new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));

    @Test
    void snapshotStartsUnsyncedWithFixturePharmacies() {
        PharmacyStockView view = service.snapshot();
        assertThat(view.lastSyncedAt()).isNull();
        assertThat(view.pharmacies()).hasSize(3);
        assertThat(view.pharmacies()).allSatisfy(pharmacy -> {
            assertThat(pharmacy.name()).isNotBlank();
            assertThat(pharmacy.region()).isNotBlank();
            assertThat(pharmacy.items()).isNotEmpty();
        });
    }

    @Test
    void syncReturnsStatsAndStampsSnapshot() {
        SyncResult result = service.sync();
        assertThat(result.pharmacyCount()).isEqualTo(3);
        // fixture 固定 3 家药店 × 各 4 条明细 = 12，硬编码期望避免复制实现的聚合逻辑
        assertThat(result.recordCount()).isEqualTo(12);
        assertThat(service.snapshot().lastSyncedAt()).isEqualTo(result.syncedAt());
    }
}

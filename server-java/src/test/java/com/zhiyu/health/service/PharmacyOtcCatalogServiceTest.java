package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zhiyu.health.entity.pharmacy.PharmacyOtcCatalogRow;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.service.pharmacy.PharmacyOtcCatalogService;
import com.zhiyu.health.service.pharmacy.mapping.PharmacyOtcCatalogMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** 药房 OTC 目录（票 95）：兜底过滤、按药房分组、无定位稳定序、有定位距离序。 */
class PharmacyOtcCatalogServiceTest {

    private final PharmacyMedicationMapper pharmacyMedicationMapper = mock(PharmacyMedicationMapper.class);
    private final PharmacyOtcCatalogService service =
            new PharmacyOtcCatalogService(pharmacyMedicationMapper, Mappers.getMapper(PharmacyOtcCatalogMapper.class));

    @Test
    void filtersPrescriptionAndOffSaleRowsButKeepsZeroStock() {
        PharmacyOtcCatalogRow otc = row(501L, 71L, 2L, "布洛芬缓释胶囊", 30);
        otc.setIsOnSale(Boolean.TRUE);
        otc.setIsPrescription(Boolean.FALSE);
        PharmacyOtcCatalogRow zeroStock = row(502L, 71L, 3L, "氯雷他定片", 0);
        zeroStock.setIsOnSale(Boolean.TRUE);
        zeroStock.setIsPrescription(Boolean.FALSE);
        PharmacyOtcCatalogRow prescription = row(503L, 71L, 12L, "阿莫西林胶囊", 10);
        prescription.setIsOnSale(Boolean.TRUE);
        prescription.setIsPrescription(Boolean.TRUE);
        PharmacyOtcCatalogRow offSale = row(504L, 71L, 5L, "维生素B2片", 10);
        offSale.setIsOnSale(Boolean.FALSE);
        offSale.setIsPrescription(Boolean.FALSE);
        when(pharmacyMedicationMapper.selectOtcCatalog()).thenReturn(List.of(otc, zeroStock, prescription, offSale));

        PharmacyOtcCatalogService.OtcCatalogView view = service.catalog(null, null);

        assertThat(view.pharmacies()).hasSize(1);
        // 处方药与停售行不出现；stock=0 行保留（前端标缺货、禁用行动按钮）
        assertThat(view.pharmacies().get(0).items())
                .extracting(PharmacyOtcCatalogService.ItemView::medicationId)
                .containsExactly(2L, 3L);
        assertThat(view.pharmacies().get(0).items().get(1).stock()).isZero();
    }

    @Test
    void groupsByPharmacyAndKeepsSqlStableOrderWithoutLocation() {
        PharmacyOtcCatalogRow a1 = row(501L, 71L, 2L, "布洛芬缓释胶囊", 30);
        a1.setHospitalName("云澜医院");
        a1.setCampusName("主院区");
        PharmacyOtcCatalogRow a2 = row(502L, 71L, 3L, "氯雷他定片", 12);
        a2.setHospitalName("云澜医院");
        a2.setCampusName("主院区");
        PharmacyOtcCatalogRow b1 = row(503L, 72L, 2L, "布洛芬缓释胶囊", 8);
        b1.setPharmacyDisplayName("东院区大药房");
        b1.setHospitalName("云澜医院");
        b1.setCampusName("东院区");
        when(pharmacyMedicationMapper.selectOtcCatalog()).thenReturn(List.of(a1, a2, b1));

        PharmacyOtcCatalogService.OtcCatalogView view = service.catalog(null, null);

        // 无定位：保持 SQL 医院/院区稳定序，不出距离
        assertThat(view.pharmacies())
                .extracting(PharmacyOtcCatalogService.PharmacyGroupView::pharmacyId)
                .containsExactly(71L, 72L);
        assertThat(view.pharmacies().get(0).distanceMeters()).isNull();
        assertThat(view.pharmacies().get(0).items())
                .extracting(PharmacyOtcCatalogService.ItemView::name)
                .containsExactly("布洛芬缓释胶囊", "氯雷他定片");
        assertThat(view.pharmacies().get(0).items().get(0).price()).isEqualByComparingTo(new BigDecimal("12.50"));
    }

    @Test
    void sortsByDistanceWhenLocatedAndPutsMissingCoordsLast() {
        PharmacyOtcCatalogRow far = row(501L, 71L, 2L, "布洛芬缓释胶囊", 30);
        far.setCampusLongitude(121.0);
        far.setCampusLatitude(31.0);
        PharmacyOtcCatalogRow noCoords = row(502L, 72L, 2L, "布洛芬缓释胶囊", 8);
        noCoords.setPharmacyDisplayName("西院区大药房");
        noCoords.setCampusLongitude(null);
        noCoords.setCampusLatitude(null);
        PharmacyOtcCatalogRow near = row(503L, 73L, 2L, "布洛芬缓释胶囊", 5);
        near.setPharmacyDisplayName("南院区大药房");
        near.setCampusLongitude(120.2);
        near.setCampusLatitude(30.3);
        // SQL 稳定序与距离序故意不一致，验证 service 重排
        when(pharmacyMedicationMapper.selectOtcCatalog()).thenReturn(List.of(far, noCoords, near));

        PharmacyOtcCatalogService.OtcCatalogView view = service.catalog(120.15, 30.27);

        assertThat(view.pharmacies())
                .extracting(PharmacyOtcCatalogService.PharmacyGroupView::pharmacyId)
                .containsExactly(73L, 71L, 72L);
        assertThat(view.pharmacies().get(0).distanceMeters()).isNotNull();
        assertThat(view.pharmacies().get(1).distanceMeters()).isNotNull();
        // 缺坐标院区排最后、距离 null，不伪造距离
        assertThat(view.pharmacies().get(2).distanceMeters()).isNull();
        // 只有一个坐标也算无定位：不出距离、保持 SQL 稳定序
        PharmacyOtcCatalogService.OtcCatalogView partial = service.catalog(120.15, null);
        assertThat(partial.pharmacies())
                .extracting(PharmacyOtcCatalogService.PharmacyGroupView::pharmacyId)
                .containsExactly(71L, 72L, 73L);
        assertThat(partial.pharmacies().get(0).distanceMeters()).isNull();
    }

    private PharmacyOtcCatalogRow row(long id, long pharmacyId, long medicationId, String name, int stock) {
        PharmacyOtcCatalogRow row = new PharmacyOtcCatalogRow();
        row.setPharmacyMedicationId(id);
        row.setPharmacyId(pharmacyId);
        row.setMedicationId(medicationId);
        row.setPrice(new BigDecimal("12.50"));
        row.setStock(stock);
        row.setIsOnSale(Boolean.TRUE);
        row.setIsPrescription(Boolean.FALSE);
        row.setMedicationName(name);
        row.setGenericName("布洛芬");
        row.setSpecification("0.3g*20粒");
        row.setPharmacyDisplayName("主院区大药房");
        row.setDeliveryFee(new BigDecimal("5.00"));
        row.setEstimatedDeliveryMinutes(45);
        row.setHospitalName("云澜医院");
        row.setCampusName("主院区");
        row.setCampusAddress("澜山市城东区梧桐路1号");
        return row;
    }
}

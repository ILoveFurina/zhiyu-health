package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.organization.HospitalCampus;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.mapper.pharmacy.CampusPharmacyMapper;
import com.zhiyu.health.service.pharmacy.CampusPharmacyService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 院区药房（票 88）：随院区创建写默认配置；配置修改只动展示名/配送费/预计时效。 */
class CampusPharmacyServiceTest {

    private final CampusPharmacyMapper pharmacyMapper = mock(CampusPharmacyMapper.class);
    private final CampusPharmacyService service = service();

    @Test
    void createForCampusWritesDefaultsFromCampus() {
        HospitalCampus campus = new HospitalCampus();
        campus.setId(11L);
        campus.setName("主院区");

        service.createForCampus(campus);

        ArgumentCaptor<CampusPharmacy> captor = ArgumentCaptor.forClass(CampusPharmacy.class);
        verify(pharmacyMapper).insert(captor.capture());
        CampusPharmacy inserted = captor.getValue();
        assertThat(inserted.getCampusId()).isEqualTo(11L);
        assertThat(inserted.getDisplayName()).isEqualTo("主院区药房");
        assertThat(inserted.getDeliveryFee()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(inserted.getEstimatedDeliveryMinutes()).isEqualTo(45);
    }

    @Test
    void requireByCampusIdRejectsMissingPharmacy() {
        when(pharmacyMapper.selectByCampusId(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.requireByCampusId(99L))
                .isInstanceOf(ApiException.class)
                .hasMessage("院区药房不存在");
    }

    @Test
    void updateConfigPersistsOnlyEditableFields() {
        CampusPharmacy existing = pharmacy(71L, 11L);
        when(pharmacyMapper.selectById(71L)).thenReturn(existing);

        CampusPharmacy updated = service.updateConfig(71L, " 主院区中心药房 ", new BigDecimal("6.50"), 60);

        ArgumentCaptor<CampusPharmacy> captor = ArgumentCaptor.forClass(CampusPharmacy.class);
        verify(pharmacyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDisplayName()).isEqualTo("主院区中心药房");
        assertThat(captor.getValue().getDeliveryFee()).isEqualByComparingTo(new BigDecimal("6.50"));
        assertThat(captor.getValue().getEstimatedDeliveryMinutes()).isEqualTo(60);
        // campus_id 一对一关系不可改
        assertThat(captor.getValue().getCampusId()).isEqualTo(11L);
        assertThat(updated.getId()).isEqualTo(71L);
    }

    @Test
    void updateConfigRejectsMissingPharmacy() {
        when(pharmacyMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateConfig(99L, "x", BigDecimal.ONE, 30))
                .isInstanceOf(ApiException.class)
                .hasMessage("院区药房不存在");
    }

    private CampusPharmacy pharmacy(long id, long campusId) {
        CampusPharmacy pharmacy = new CampusPharmacy();
        pharmacy.setId(id);
        pharmacy.setCampusId(campusId);
        pharmacy.setDisplayName("主院区药房");
        pharmacy.setDeliveryFee(new BigDecimal("5.00"));
        pharmacy.setEstimatedDeliveryMinutes(45);
        return pharmacy;
    }

    private CampusPharmacyService service() {
        CampusPharmacyService service = new CampusPharmacyService();
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", pharmacyMapper);
        return service;
    }
}

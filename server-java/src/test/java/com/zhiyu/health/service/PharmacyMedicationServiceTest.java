package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.entity.pharmacy.PharmacyMedication;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderItemMapper;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.service.pharmacy.CampusPharmacyService;
import com.zhiyu.health.service.pharmacy.PharmacyMedicationService;
import com.zhiyu.health.service.pharmacy.mapping.PharmacyDtoMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

/** 院区药房库存（票 88）：按药房隔离的目录维护与「历史引用只下架不删」规则。 */
class PharmacyMedicationServiceTest {

    private final CampusPharmacyService campusPharmacyService = mock(CampusPharmacyService.class);
    private final MedicationMapper medicationMapper = mock(MedicationMapper.class);
    private final DrugOrderItemMapper drugOrderItemMapper = mock(DrugOrderItemMapper.class);
    private final PharmacyMedicationMapper pharmacyMedicationMapper = mock(PharmacyMedicationMapper.class);
    private final PharmacyMedicationService service = service();

    @Test
    void listMedicationsScopesByPharmacy() {
        when(pharmacyMedicationMapper.selectDetailedByPharmacy(71L, null)).thenReturn(List.of(row(501L, 71L, 12L)));

        List<PharmacyMedicationService.PharmacyMedicationView> views = service.listMedications(71L, null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).name()).isEqualTo("阿莫西林胶囊");
        assertThat(views.get(0).price()).isEqualByComparingTo(new BigDecimal("12.50"));
        // 跨院区库存隔离：查询只经本药房 id，绝不返回其他药房关系
        verify(pharmacyMedicationMapper).selectDetailedByPharmacy(71L, null);
    }

    @Test
    void listMedicationsRejectsMissingPharmacy() {
        when(campusPharmacyService.requireById(99L)).thenThrow(new ApiException(404, "院区药房不存在"));

        assertThatThrownBy(() -> service.listMedications(99L, null))
                .isInstanceOf(ApiException.class)
                .hasMessage("院区药房不存在");
    }

    @Test
    void addRejectsUnknownMedication() {
        when(medicationMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.add(
                        71L, new PharmacyMedicationService.AddCommand(99L, new BigDecimal("9.90"), 10, Boolean.TRUE)))
                .isInstanceOf(ApiException.class)
                .hasMessage("药品不存在");
        verify(pharmacyMedicationMapper, never()).insert(any(PharmacyMedication.class));
    }

    @Test
    void addTranslatesDuplicatePairToConflict() {
        when(medicationMapper.selectById(12L)).thenReturn(new Medication());
        // 并发/重复加入撞 uq_pharmacy_medications_pair：明确 409，不冒 500
        when(pharmacyMedicationMapper.insert(any(PharmacyMedication.class)))
                .thenThrow(new DuplicateKeyException("uq_pharmacy_medications_pair"));

        assertThatThrownBy(() -> service.add(
                        71L, new PharmacyMedicationService.AddCommand(12L, new BigDecimal("9.90"), 10, Boolean.TRUE)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("该药品已在药房目录中");
                });
    }

    @Test
    void addPersistsRowAndReturnsDetailedView() {
        when(medicationMapper.selectById(12L)).thenReturn(new Medication());
        // mock 不回填自增 id，模拟 MP 插入后 id 回填行为
        org.mockito.Mockito.doAnswer(invocation -> {
                    invocation.getArgument(0, PharmacyMedication.class).setId(501L);
                    return 1;
                })
                .when(pharmacyMedicationMapper)
                .insert(any(PharmacyMedication.class));
        when(pharmacyMedicationMapper.selectDetailedById(501L)).thenReturn(row(501L, 71L, 12L));

        PharmacyMedicationService.PharmacyMedicationView view = service.add(
                71L, new PharmacyMedicationService.AddCommand(12L, new BigDecimal("12.50"), 30, Boolean.TRUE));

        ArgumentCaptor<PharmacyMedication> captor = ArgumentCaptor.forClass(PharmacyMedication.class);
        verify(pharmacyMedicationMapper).insert(captor.capture());
        assertThat(captor.getValue().getPharmacyId()).isEqualTo(71L);
        assertThat(captor.getValue().getMedicationId()).isEqualTo(12L);
        assertThat(view.name()).isEqualTo("阿莫西林胶囊");
    }

    @Test
    void updateRejectsMissingRow() {
        // 按 id 定位（路由不再携带 pharmacyId），不存在统一 404
        when(pharmacyMedicationMapper.selectById(501L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(
                        501L, new PharmacyMedicationService.UpdateCommand(new BigDecimal("9.90"), 5, Boolean.FALSE)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        verify(pharmacyMedicationMapper, never()).updateById(any(PharmacyMedication.class));
    }

    @Test
    void updatePersistsEditableFields() {
        when(pharmacyMedicationMapper.selectById(501L)).thenReturn(row(501L, 71L, 12L));
        PharmacyMedication updated = row(501L, 71L, 12L);
        updated.setPrice(new BigDecimal("9.90"));
        updated.setStock(5);
        updated.setIsOnSale(Boolean.FALSE);
        when(pharmacyMedicationMapper.selectDetailedById(501L)).thenReturn(updated);

        PharmacyMedicationService.PharmacyMedicationView view = service.update(
                501L, new PharmacyMedicationService.UpdateCommand(new BigDecimal("9.90"), 5, Boolean.FALSE));

        ArgumentCaptor<PharmacyMedication> captor = ArgumentCaptor.forClass(PharmacyMedication.class);
        verify(pharmacyMedicationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getIsOnSale()).isFalse();
        assertThat(view.isOnSale()).isFalse();
    }

    @Test
    void removeRejectsWhenReferencedByOrders() {
        when(pharmacyMedicationMapper.selectById(501L)).thenReturn(row(501L, 71L, 12L));
        when(drugOrderItemMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.remove(501L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo("该药品已被订单引用，仅可下架");
        });
        verify(pharmacyMedicationMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void removeRejectsWhenReferencedByCampusPrescriptions() {
        when(pharmacyMedicationMapper.selectById(501L)).thenReturn(row(501L, 71L, 12L));
        when(drugOrderItemMapper.selectCount(any())).thenReturn(0L);
        CampusPharmacy pharmacy = new CampusPharmacy();
        pharmacy.setId(71L);
        pharmacy.setCampusId(11L);
        when(campusPharmacyService.requireById(71L)).thenReturn(pharmacy);
        // 处方引用按「同药品 + 处方来源院区 = 本药房院区」判定
        when(pharmacyMedicationMapper.countPrescriptionReferences(12L, 11L)).thenReturn(2L);

        assertThatThrownBy(() -> service.remove(501L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo("该药品已被处方引用，仅可下架");
        });
        verify(pharmacyMedicationMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void removeDeletesWhenNeverReferenced() {
        when(pharmacyMedicationMapper.selectById(501L)).thenReturn(row(501L, 71L, 12L));
        when(drugOrderItemMapper.selectCount(any())).thenReturn(0L);
        CampusPharmacy pharmacy = new CampusPharmacy();
        pharmacy.setId(71L);
        pharmacy.setCampusId(11L);
        when(campusPharmacyService.requireById(71L)).thenReturn(pharmacy);
        when(pharmacyMedicationMapper.countPrescriptionReferences(12L, 11L)).thenReturn(0L);

        service.remove(501L);

        verify(pharmacyMedicationMapper).deleteById(501L);
    }

    private PharmacyMedication row(long id, long pharmacyId, long medicationId) {
        PharmacyMedication row = new PharmacyMedication();
        row.setId(id);
        row.setPharmacyId(pharmacyId);
        row.setMedicationId(medicationId);
        row.setPrice(new BigDecimal("12.50"));
        row.setStock(30);
        row.setIsOnSale(Boolean.TRUE);
        row.setMedicationName("阿莫西林胶囊");
        row.setSpecification("0.25g*24粒");
        row.setIsPrescription(Boolean.TRUE);
        return row;
    }

    private PharmacyMedicationService service() {
        PharmacyMedicationService service = new PharmacyMedicationService(
                campusPharmacyService,
                medicationMapper,
                drugOrderItemMapper,
                Mappers.getMapper(PharmacyDtoMapper.class));
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", pharmacyMedicationMapper);
        return service;
    }
}

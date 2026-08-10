package com.zhiyu.health.service.pharmacy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.entity.pharmacy.PharmacyMedication;
import com.zhiyu.health.entity.prescription.DrugOrderItem;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderItemMapper;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.service.pharmacy.mapping.PharmacyDtoMapper;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 院区药房库存（票 88，ADR-0035）：admin/pharmacist 维护某院区药房的药品在售关系。
 * 删除规则：从未被处方明细（同药品 + 处方来源院区属本药房）或订单明细引用的关系才允许
 * 物理删除；已有历史引用只允许下架/重新上架（409 引导走更新）。
 */
@Service
@RequiredArgsConstructor
public class PharmacyMedicationService extends ServiceImpl<PharmacyMedicationMapper, PharmacyMedication> {

    private final CampusPharmacyService campusPharmacyService;
    private final MedicationMapper medicationMapper;
    private final DrugOrderItemMapper drugOrderItemMapper;
    private final PharmacyDtoMapper dtoMapper;

    public List<PharmacyMedicationView> listMedications(long pharmacyId, String keyword) {
        campusPharmacyService.requireById(pharmacyId);
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return getBaseMapper().selectDetailedByPharmacy(pharmacyId, normalized).stream()
                .map(dtoMapper::toView)
                .toList();
    }

    public PharmacyMedicationView add(long pharmacyId, AddCommand command) {
        campusPharmacyService.requireById(pharmacyId);
        if (medicationMapper.selectById(command.medicationId()) == null) {
            throw new ApiException(400, "药品不存在");
        }
        PharmacyMedication row = dtoMapper.toEntity(command, pharmacyId);
        try {
            save(row);
        } catch (DuplicateKeyException e) {
            // 并发/重复提交撞 uq_pharmacy_medications_pair：明确冲突，不冒 500。
            throw new ApiException(409, "该药品已在药房目录中");
        }
        return dtoMapper.toView(getBaseMapper().selectDetailedById(row.getId()));
    }

    public PharmacyMedicationView update(long id, UpdateCommand command) {
        requireExisting(id);
        PharmacyMedication row = new PharmacyMedication();
        row.setId(id);
        row.setPrice(command.price());
        row.setStock(command.stock());
        row.setIsOnSale(command.isOnSale());
        updateById(row);
        return dtoMapper.toView(getBaseMapper().selectDetailedById(id));
    }

    public void remove(long id) {
        PharmacyMedication row = requireExisting(id);
        Long orderReferences = drugOrderItemMapper.selectCount(
                Wrappers.<DrugOrderItem>lambdaQuery().eq(DrugOrderItem::getPharmacyMedicationId, id));
        if (orderReferences != null && orderReferences > 0) {
            throw new ApiException(409, "该药品已被订单引用，仅可下架");
        }
        CampusPharmacy pharmacy = campusPharmacyService.requireById(row.getPharmacyId());
        if (getBaseMapper().countPrescriptionReferences(row.getMedicationId(), pharmacy.getCampusId()) > 0) {
            throw new ApiException(409, "该药品已被处方引用，仅可下架");
        }
        removeById(id);
    }

    /** 按 id 定位在售关系（路由不再携带 pharmacyId）；不存在统一 404。 */
    private PharmacyMedication requireExisting(long id) {
        PharmacyMedication row = getById(id);
        if (row == null) {
            throw new ApiException(404, "药房药品不存在");
        }
        return row;
    }

    /** 视图字段对齐 admin 消费：药品名列为 name（实体列 medication_name 由 MapStruct 改名）。 */
    public record PharmacyMedicationView(
            Long id,
            Long pharmacyId,
            Long medicationId,
            String name,
            String genericName,
            String specification,
            Boolean isPrescription,
            BigDecimal price,
            Integer stock,
            Boolean isOnSale) {}

    public record AddCommand(long medicationId, BigDecimal price, Integer stock, Boolean isOnSale) {}

    public record UpdateCommand(BigDecimal price, Integer stock, Boolean isOnSale) {}
}

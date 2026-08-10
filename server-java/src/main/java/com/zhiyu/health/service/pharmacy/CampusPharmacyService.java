package com.zhiyu.health.service.pharmacy;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.organization.HospitalCampus;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.mapper.pharmacy.CampusPharmacyMapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 院区药房（票 88，ADR-0035）：与院区强一对一，只随院区创建事务自动创建，
 * 不提供独立新增/删除入口；admin 仅可改展示名、配送费与预计配送分钟数。
 */
@Service
public class CampusPharmacyService extends ServiceImpl<CampusPharmacyMapper, CampusPharmacy> {

    /** 新建药房的虚构默认配送报价（演示数据口径与 seed 一致量级）。 */
    private static final BigDecimal DEFAULT_DELIVERY_FEE = new BigDecimal("5.00");

    private static final int DEFAULT_ESTIMATED_MINUTES = 45;

    /** 由 CampusAdminService.create 在同事务内调用；campus.id 须已由 insert 回填。 */
    public CampusPharmacy createForCampus(HospitalCampus campus) {
        CampusPharmacy pharmacy = new CampusPharmacy();
        pharmacy.setCampusId(campus.getId());
        pharmacy.setDisplayName(campus.getName() + "药房");
        pharmacy.setDeliveryFee(DEFAULT_DELIVERY_FEE);
        pharmacy.setEstimatedDeliveryMinutes(DEFAULT_ESTIMATED_MINUTES);
        save(pharmacy);
        return pharmacy;
    }

    public List<CampusPharmacy> listAll() {
        return list();
    }

    public CampusPharmacy requireByCampusId(long campusId) {
        CampusPharmacy pharmacy = getBaseMapper().selectByCampusId(campusId);
        if (pharmacy == null) {
            throw new ApiException(404, "院区药房不存在");
        }
        return pharmacy;
    }

    public CampusPharmacy requireById(long pharmacyId) {
        CampusPharmacy pharmacy = getById(pharmacyId);
        if (pharmacy == null) {
            throw new ApiException(404, "院区药房不存在");
        }
        return pharmacy;
    }

    /** 只允许修改展示名、配送费与预计时效；campus_id 一对一关系不可改。 */
    public CampusPharmacy updateConfig(
            long pharmacyId, String displayName, BigDecimal deliveryFee, Integer estimatedDeliveryMinutes) {
        CampusPharmacy pharmacy = requireById(pharmacyId);
        pharmacy.setDisplayName(displayName.trim());
        pharmacy.setDeliveryFee(deliveryFee);
        pharmacy.setEstimatedDeliveryMinutes(estimatedDeliveryMinutes);
        updateById(pharmacy);
        return getById(pharmacy.getId());
    }
}

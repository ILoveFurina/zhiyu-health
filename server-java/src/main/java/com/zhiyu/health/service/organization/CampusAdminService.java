package com.zhiyu.health.service.organization;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.organization.Department;
import com.zhiyu.health.entity.organization.HospitalCampus;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.entity.pharmacy.PharmacyMedication;
import com.zhiyu.health.mapper.organization.DepartmentMapper;
import com.zhiyu.health.mapper.organization.HospitalCampusMapper;
import com.zhiyu.health.mapper.organization.HospitalMapper;
import com.zhiyu.health.mapper.pharmacy.CampusPharmacyMapper;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.service.pharmacy.CampusPharmacyService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B 端院区管理（票 49）：CRUD 由 ServiceImpl 提供；医院外键写入前校验，删除受科室引用限制。
 * 票 88（ADR-0035）：院区与药房强一对一——创建同事务自动建药房，删除同事务清理药房关系。
 */
@Service
@RequiredArgsConstructor
public class CampusAdminService extends ServiceImpl<HospitalCampusMapper, HospitalCampus> {

    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;
    private final CampusPharmacyService campusPharmacyService;
    private final CampusPharmacyMapper campusPharmacyMapper;
    private final PharmacyMedicationMapper pharmacyMedicationMapper;

    public List<HospitalCampus> listAll() {
        return list(new QueryWrapper<HospitalCampus>().orderByAsc("id"));
    }

    /** 院区与药房同生共死：任一写失败整体回滚，不留「有院区无药房」中间态。 */
    @Transactional
    public HospitalCampus create(HospitalCampus campus) {
        if (hospitalMapper.selectById(campus.getHospitalId()) == null) {
            throw new ApiException(404, "医院不存在");
        }
        save(campus);
        campusPharmacyService.createForCampus(campus);
        return campus;
    }

    public HospitalCampus update(HospitalCampus campus) {
        if (getById(campus.getId()) == null || hospitalMapper.selectById(campus.getHospitalId()) == null) {
            throw new ApiException(404, "院区或医院不存在");
        }
        updateById(campus);
        return getById(campus.getId());
    }

    @Transactional
    public void delete(long campusId) {
        if (getById(campusId) == null) {
            throw new ApiException(404, "院区不存在");
        }
        // 全链限制删除（票 49）：院区下存在实际科室即拒绝删除，避免孤儿科室/排班。
        Long departments =
                departmentMapper.selectCount(Wrappers.<Department>lambdaQuery().eq(Department::getCampusId, campusId));
        if (departments != null && departments > 0) {
            throw new ApiException(409, "院区下存在科室，无法删除");
        }
        try {
            // 药房随院区同事务清理（先药品关系后药房）；历史处方（source_campus_id）或
            // 订单（pharmacy_id）仍引用本院区时由外键兜底，整体回滚并给出可理解的 409。
            CampusPharmacy pharmacy = campusPharmacyMapper.selectByCampusId(campusId);
            if (pharmacy != null) {
                pharmacyMedicationMapper.delete(Wrappers.<PharmacyMedication>lambdaQuery()
                        .eq(PharmacyMedication::getPharmacyId, pharmacy.getId()));
                campusPharmacyMapper.deleteById(pharmacy.getId());
            }
            removeById(campusId);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(409, "院区存在历史业务数据，无法删除");
        }
    }
}

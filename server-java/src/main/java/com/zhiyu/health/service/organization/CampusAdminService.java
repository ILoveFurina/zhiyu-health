package com.zhiyu.health.service.organization;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.organization.Department;
import com.zhiyu.health.entity.organization.HospitalCampus;
import com.zhiyu.health.mapper.organization.DepartmentMapper;
import com.zhiyu.health.mapper.organization.HospitalCampusMapper;
import com.zhiyu.health.mapper.organization.HospitalMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** B 端院区管理（票 49）：CRUD 由 ServiceImpl 提供；医院外键写入前校验，删除受科室引用限制。 */
@Service
@RequiredArgsConstructor
public class CampusAdminService extends ServiceImpl<HospitalCampusMapper, HospitalCampus> {

    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;

    public List<HospitalCampus> listAll() {
        return list(new QueryWrapper<HospitalCampus>().orderByAsc("id"));
    }

    public HospitalCampus create(HospitalCampus campus) {
        if (hospitalMapper.selectById(campus.getHospitalId()) == null) {
            throw new ApiException(404, "医院不存在");
        }
        save(campus);
        return campus;
    }

    public HospitalCampus update(HospitalCampus campus) {
        if (getById(campus.getId()) == null || hospitalMapper.selectById(campus.getHospitalId()) == null) {
            throw new ApiException(404, "院区或医院不存在");
        }
        updateById(campus);
        return getById(campus.getId());
    }

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
        removeById(campusId);
    }
}

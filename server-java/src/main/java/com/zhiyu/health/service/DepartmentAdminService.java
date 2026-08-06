package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Department;
import com.zhiyu.health.entity.DepartmentCategory;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.HospitalCampus;
import com.zhiyu.health.mapper.DepartmentCategoryMapper;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.HospitalCampusMapper;
import com.zhiyu.health.mapper.StandardDepartmentMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** B 端科室管理：CRUD 由 ServiceImpl 提供；院区/分类/标准科室外键在写入前校验，缺失即抛 404。 */
@Service
@RequiredArgsConstructor
public class DepartmentAdminService extends ServiceImpl<DepartmentMapper, Department> {

    private final HospitalCampusMapper hospitalCampusMapper;
    private final DepartmentCategoryMapper departmentCategoryMapper;
    private final StandardDepartmentMapper standardDepartmentMapper;
    private final DoctorMapper doctorMapper;

    public List<Department> listAll() {
        return list(new QueryWrapper<Department>().orderByAsc("id"));
    }

    public Department create(Department department) {
        validateReferences(department);
        save(department);
        return department;
    }

    public Department update(Department department) {
        if (getById(department.getId()) == null) {
            throw new ApiException(404, "科室不存在");
        }
        validateReferences(department);
        updateById(department);
        return getById(department.getId());
    }

    public void delete(long departmentId) {
        if (getById(departmentId) == null) {
            throw new ApiException(404, "科室不存在");
        }
        // 全链限制删除（票 49）：科室下存在医生即拒绝删除，避免孤儿医生/排班/挂号。
        Long doctors =
                doctorMapper.selectCount(Wrappers.<Doctor>lambdaQuery().eq(Doctor::getDepartmentId, departmentId));
        if (doctors != null && doctors > 0) {
            throw new ApiException(409, "科室下存在医生，无法删除");
        }
        removeById(departmentId);
    }

    /**
     * 三个外键分别校验存在性（404）；院区与分类必须同属一家医院（400），
     * DB 不跨表约束，该业务一致性由 service 保证（schema.sql departments 注释）。
     */
    private void validateReferences(Department department) {
        HospitalCampus campus = hospitalCampusMapper.selectById(department.getCampusId());
        if (campus == null) {
            throw new ApiException(404, "院区不存在");
        }
        DepartmentCategory category = departmentCategoryMapper.selectById(department.getCategoryId());
        if (category == null) {
            throw new ApiException(404, "科室分类不存在");
        }
        if (standardDepartmentMapper.selectById(department.getStandardDepartmentId()) == null) {
            throw new ApiException(404, "标准科室不存在");
        }
        if (!campus.getHospitalId().equals(category.getHospitalId())) {
            throw new ApiException(400, "院区与科室分类不属于同一医院");
        }
    }
}

package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Department;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** B 端科室管理：CRUD 由 ServiceImpl 提供；医院外键在写入前校验，缺失即抛 404。 */
@Service
@RequiredArgsConstructor
public class DepartmentAdminService extends ServiceImpl<DepartmentMapper, Department> {

    private final HospitalMapper hospitalMapper;

    public List<Department> listAll() {
        return list(new QueryWrapper<Department>().orderByAsc("id"));
    }

    public Department create(Department department) {
        if (hospitalMapper.selectById(department.getHospitalId()) == null) {
            throw new ApiException(404, "医院不存在");
        }
        save(department);
        return department;
    }

    public Department update(Department department) {
        if (getById(department.getId()) == null || hospitalMapper.selectById(department.getHospitalId()) == null) {
            throw new ApiException(404, "科室或医院不存在");
        }
        updateById(department);
        return getById(department.getId());
    }

    public void delete(long departmentId) {
        if (!removeById(departmentId)) {
            throw new ApiException(404, "科室不存在");
        }
    }
}

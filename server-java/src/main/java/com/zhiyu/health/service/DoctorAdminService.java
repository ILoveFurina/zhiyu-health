package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.DoctorMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** B 端医生管理：CRUD 由 ServiceImpl 提供；科室外键在写入前校验，缺失即抛 404。 */
@Service
@RequiredArgsConstructor
public class DoctorAdminService extends ServiceImpl<DoctorMapper, Doctor> {

    private final DepartmentMapper departmentMapper;

    public List<Doctor> listAll() {
        return list(new QueryWrapper<Doctor>().orderByAsc("id"));
    }

    public Doctor create(Doctor doctor) {
        if (departmentMapper.selectById(doctor.getDepartmentId()) == null) {
            throw new ApiException(404, "科室不存在");
        }
        save(doctor);
        return doctor;
    }

    public Doctor update(Doctor doctor) {
        if (getById(doctor.getId()) == null || departmentMapper.selectById(doctor.getDepartmentId()) == null) {
            throw new ApiException(404, "医生或科室不存在");
        }
        updateById(doctor);
        return getById(doctor.getId());
    }

    public void delete(long doctorId) {
        if (!removeById(doctorId)) {
            throw new ApiException(404, "医生不存在");
        }
    }
}

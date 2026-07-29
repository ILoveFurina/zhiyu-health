package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Department;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 组织管理（医院/科室/医生）：外键归属在写入前校验，缺失即抛 404，由 ApiExceptionHandler 统一出口 */
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;
    private final DoctorMapper doctorMapper;

    public List<Hospital> listHospitals() {
        return hospitalMapper.selectList(new QueryWrapper<Hospital>().orderByAsc("id"));
    }

    public Hospital createHospital(Hospital hospital) {
        hospitalMapper.insert(hospital);
        return hospital;
    }

    public Hospital updateHospital(Hospital hospital) {
        if (hospitalMapper.selectById(hospital.getId()) == null) {
            throw new ApiException(404, "医院不存在");
        }
        hospitalMapper.updateById(hospital);
        return hospitalMapper.selectById(hospital.getId());
    }

    public void deleteHospital(long hospitalId) {
        if (hospitalMapper.deleteById(hospitalId) == 0) {
            throw new ApiException(404, "医院不存在");
        }
    }

    public List<Department> listDepartments() {
        return departmentMapper.selectList(new QueryWrapper<Department>().orderByAsc("id"));
    }

    public Department createDepartment(Department department) {
        if (hospitalMapper.selectById(department.getHospitalId()) == null) {
            throw new ApiException(404, "医院不存在");
        }
        departmentMapper.insert(department);
        return department;
    }

    public Department updateDepartment(Department department) {
        if (departmentMapper.selectById(department.getId()) == null
                || hospitalMapper.selectById(department.getHospitalId()) == null) {
            throw new ApiException(404, "科室或医院不存在");
        }
        departmentMapper.updateById(department);
        return departmentMapper.selectById(department.getId());
    }

    public void deleteDepartment(long departmentId) {
        if (departmentMapper.deleteById(departmentId) == 0) {
            throw new ApiException(404, "科室不存在");
        }
    }

    public List<Doctor> listDoctors() {
        return doctorMapper.selectList(new QueryWrapper<Doctor>().orderByAsc("id"));
    }

    public Doctor createDoctor(Doctor doctor) {
        if (departmentMapper.selectById(doctor.getDepartmentId()) == null) {
            throw new ApiException(404, "科室不存在");
        }
        doctorMapper.insert(doctor);
        return doctor;
    }

    public Doctor updateDoctor(Doctor doctor) {
        if (doctorMapper.selectById(doctor.getId()) == null
                || departmentMapper.selectById(doctor.getDepartmentId()) == null) {
            throw new ApiException(404, "医生或科室不存在");
        }
        doctorMapper.updateById(doctor);
        return doctorMapper.selectById(doctor.getId());
    }

    public void deleteDoctor(long doctorId) {
        if (doctorMapper.deleteById(doctorId) == 0) {
            throw new ApiException(404, "医生不存在");
        }
    }
}

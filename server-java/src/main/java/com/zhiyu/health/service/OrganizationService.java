package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiyu.health.entity.Department;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 组织管理（医院/科室/医生）：外键归属在写入前校验，缺失返回 null 由 HTTP 层映射 404 */
@Service
public class OrganizationService {

    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;
    private final DoctorMapper doctorMapper;

    public OrganizationService(HospitalMapper hospitalMapper,
                               DepartmentMapper departmentMapper,
                               DoctorMapper doctorMapper) {
        this.hospitalMapper = hospitalMapper;
        this.departmentMapper = departmentMapper;
        this.doctorMapper = doctorMapper;
    }

    public List<Hospital> listHospitals() {
        return hospitalMapper.selectList(new QueryWrapper<Hospital>().orderByAsc("id"));
    }

    public Hospital createHospital(Hospital hospital) {
        hospitalMapper.insert(hospital);
        return hospital;
    }

    public Hospital updateHospital(Hospital hospital) {
        if (hospitalMapper.selectById(hospital.getId()) == null) {
            return null;
        }
        hospitalMapper.updateById(hospital);
        return hospitalMapper.selectById(hospital.getId());
    }

    public boolean deleteHospital(long hospitalId) {
        return hospitalMapper.deleteById(hospitalId) > 0;
    }

    public List<Department> listDepartments() {
        return departmentMapper.selectList(new QueryWrapper<Department>().orderByAsc("id"));
    }

    public Department createDepartment(Department department) {
        if (hospitalMapper.selectById(department.getHospitalId()) == null) {
            return null;
        }
        departmentMapper.insert(department);
        return department;
    }

    public Department updateDepartment(Department department) {
        if (departmentMapper.selectById(department.getId()) == null
                || hospitalMapper.selectById(department.getHospitalId()) == null) {
            return null;
        }
        departmentMapper.updateById(department);
        return departmentMapper.selectById(department.getId());
    }

    public boolean deleteDepartment(long departmentId) {
        return departmentMapper.deleteById(departmentId) > 0;
    }

    public List<Doctor> listDoctors() {
        return doctorMapper.selectList(new QueryWrapper<Doctor>().orderByAsc("id"));
    }

    public Doctor createDoctor(Doctor doctor) {
        if (departmentMapper.selectById(doctor.getDepartmentId()) == null) {
            return null;
        }
        doctorMapper.insert(doctor);
        return doctor;
    }

    public Doctor updateDoctor(Doctor doctor) {
        if (doctorMapper.selectById(doctor.getId()) == null
                || departmentMapper.selectById(doctor.getDepartmentId()) == null) {
            return null;
        }
        doctorMapper.updateById(doctor);
        return doctorMapper.selectById(doctor.getId());
    }

    public boolean deleteDoctor(long doctorId) {
        return doctorMapper.deleteById(doctorId) > 0;
    }
}

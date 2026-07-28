package com.zhiyu.health.service;

import com.zhiyu.health.entity.Department;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 组织管理：外键归属校验，缺失不落库返回 null（HTTP 层映射 404） */
class OrganizationServiceTest {

    private final HospitalMapper hospitalMapper = mock(HospitalMapper.class);
    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final DoctorMapper doctorMapper = mock(DoctorMapper.class);
    private final OrganizationService service =
            new OrganizationService(hospitalMapper, departmentMapper, doctorMapper);

    @Test
    void createDepartmentInsertsWhenHospitalExists() {
        when(hospitalMapper.selectById(1L)).thenReturn(new Hospital());
        Department department = new Department();
        department.setHospitalId(1L);

        assertThat(service.createDepartment(department)).isSameAs(department);
        verify(departmentMapper).insert(department);
    }

    @Test
    void createDepartmentReturnsNullWhenHospitalMissing() {
        when(hospitalMapper.selectById(99L)).thenReturn(null);
        Department department = new Department();
        department.setHospitalId(99L);

        assertThat(service.createDepartment(department)).isNull();
        verify(departmentMapper, never()).insert(any(Department.class));
    }

    @Test
    void createDoctorReturnsNullWhenDepartmentMissing() {
        when(departmentMapper.selectById(99L)).thenReturn(null);
        Doctor doctor = new Doctor();
        doctor.setDepartmentId(99L);

        assertThat(service.createDoctor(doctor)).isNull();
        verify(doctorMapper, never()).insert(any(Doctor.class));
    }

    @Test
    void updateDoctorReturnsNullWhenDoctorMissing() {
        when(doctorMapper.selectById(99L)).thenReturn(null);
        Doctor doctor = new Doctor();
        doctor.setId(99L);
        doctor.setDepartmentId(1L);

        assertThat(service.updateDoctor(doctor)).isNull();
        verify(doctorMapper, never()).updateById(any(Doctor.class));
    }

    @Test
    void deleteHospitalFalseWhenNothingDeleted() {
        when(hospitalMapper.deleteById(99L)).thenReturn(0);

        assertThat(service.deleteHospital(99L)).isFalse();
    }
}

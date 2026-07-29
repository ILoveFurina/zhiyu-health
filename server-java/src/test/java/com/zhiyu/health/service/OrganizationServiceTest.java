package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Department;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 组织管理：外键归属校验，缺失不落库并抛 404（ApiExceptionHandler 统一出口） */
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
    void createDepartmentRejectsWhenHospitalMissing() {
        when(hospitalMapper.selectById(99L)).thenReturn(null);
        Department department = new Department();
        department.setHospitalId(99L);

        assertThatThrownBy(() -> service.createDepartment(department))
                .isInstanceOf(ApiException.class)
                .hasMessage("医院不存在");
        verify(departmentMapper, never()).insert(any(Department.class));
    }

    @Test
    void createDoctorRejectsWhenDepartmentMissing() {
        when(departmentMapper.selectById(99L)).thenReturn(null);
        Doctor doctor = new Doctor();
        doctor.setDepartmentId(99L);

        assertThatThrownBy(() -> service.createDoctor(doctor))
                .isInstanceOf(ApiException.class)
                .hasMessage("科室不存在");
        verify(doctorMapper, never()).insert(any(Doctor.class));
    }

    @Test
    void updateDoctorRejectsWhenDoctorMissing() {
        when(doctorMapper.selectById(99L)).thenReturn(null);
        Doctor doctor = new Doctor();
        doctor.setId(99L);
        doctor.setDepartmentId(1L);

        assertThatThrownBy(() -> service.updateDoctor(doctor))
                .isInstanceOf(ApiException.class)
                .hasMessage("医生或科室不存在");
        verify(doctorMapper, never()).updateById(any(Doctor.class));
    }

    @Test
    void deleteHospitalRejectsWhenNothingDeleted() {
        when(hospitalMapper.deleteById(99L)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteHospital(99L))
                .isInstanceOf(ApiException.class)
                .hasMessage("医院不存在");
    }
}

package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.organization.Doctor;
import com.zhiyu.health.mapper.organization.DepartmentMapper;
import com.zhiyu.health.mapper.organization.DoctorMapper;
import com.zhiyu.health.mapper.scheduling.ScheduleMapper;
import com.zhiyu.health.service.organization.DoctorAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** B 端医生管理：科室外键校验 404；存在排班删除 409（票 49 全链限制删除） */
class DoctorAdminServiceTest {

    private final DoctorMapper doctorMapper = mock(DoctorMapper.class);
    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final ScheduleMapper scheduleMapper = mock(ScheduleMapper.class);
    private final DoctorAdminService service = service();

    @Test
    void createDoctorRejectsWhenDepartmentMissing() {
        when(departmentMapper.selectById(99L)).thenReturn(null);
        Doctor doctor = new Doctor();
        doctor.setDepartmentId(99L);

        assertThatThrownBy(() -> service.create(doctor))
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

        assertThatThrownBy(() -> service.update(doctor))
                .isInstanceOf(ApiException.class)
                .hasMessage("医生或科室不存在");
        verify(doctorMapper, never()).updateById(any(Doctor.class));
    }

    @Test
    void deleteDoctorRejects409WhenSchedulesExist() {
        Doctor doctor = new Doctor();
        doctor.setId(1L);
        when(doctorMapper.selectById(1L)).thenReturn(doctor);
        when(scheduleMapper.selectCount(any())).thenReturn(5L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(ApiException.class)
                .hasMessage("医生存在排班，无法删除");
        verify(doctorMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteDoctorProceedsWhenNoSchedules() {
        Doctor doctor = new Doctor();
        doctor.setId(1L);
        when(doctorMapper.selectById(1L)).thenReturn(doctor);
        when(scheduleMapper.selectCount(any())).thenReturn(0L);
        when(doctorMapper.deleteById(1L)).thenReturn(1);

        service.delete(1L);
        verify(doctorMapper).deleteById(1L);
    }

    private DoctorAdminService service() {
        DoctorAdminService service = new DoctorAdminService(departmentMapper, scheduleMapper);
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", doctorMapper);
        return service;
    }
}

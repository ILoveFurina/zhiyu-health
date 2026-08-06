package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.entity.HospitalCampus;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.HospitalCampusMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** B 端院区管理（票 49）：医院外键 404；存在科室删除 409 */
class CampusAdminServiceTest {

    private final HospitalCampusMapper campusMapper = mock(HospitalCampusMapper.class);
    private final HospitalMapper hospitalMapper = mock(HospitalMapper.class);
    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final CampusAdminService service = service();

    @Test
    void createCampusRejectsWhenHospitalMissing() {
        when(hospitalMapper.selectById(99L)).thenReturn(null);
        HospitalCampus campus = new HospitalCampus();
        campus.setHospitalId(99L);

        assertThatThrownBy(() -> service.create(campus))
                .isInstanceOf(ApiException.class)
                .hasMessage("医院不存在");
        verify(campusMapper, never()).insert(any(HospitalCampus.class));
    }

    @Test
    void createCampusInsertsWhenHospitalExists() {
        when(hospitalMapper.selectById(1L)).thenReturn(new Hospital());
        HospitalCampus campus = new HospitalCampus();
        campus.setHospitalId(1L);

        service.create(campus);
        verify(campusMapper).insert(campus);
    }

    @Test
    void deleteCampusRejects409WhenDepartmentsExist() {
        HospitalCampus campus = new HospitalCampus();
        campus.setId(11L);
        when(campusMapper.selectById(11L)).thenReturn(campus);
        when(departmentMapper.selectCount(any())).thenReturn(4L);

        assertThatThrownBy(() -> service.delete(11L))
                .isInstanceOf(ApiException.class)
                .hasMessage("院区下存在科室，无法删除");
        verify(campusMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteCampusRejects404WhenMissing() {
        when(campusMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ApiException.class)
                .hasMessage("院区不存在");
        verify(campusMapper, never()).deleteById(any(Long.class));
    }

    private CampusAdminService service() {
        CampusAdminService service = new CampusAdminService(hospitalMapper, departmentMapper);
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", campusMapper);
        return service;
    }
}

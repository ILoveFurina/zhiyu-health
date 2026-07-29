package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Department;
import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** B 端科室管理：医院外键校验，缺失不落库并抛 404（ApiExceptionHandler 统一出口） */
class DepartmentAdminServiceTest {

    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final HospitalMapper hospitalMapper = mock(HospitalMapper.class);
    private final DepartmentAdminService service = service();

    @Test
    void createDepartmentInsertsWhenHospitalExists() {
        when(hospitalMapper.selectById(1L)).thenReturn(new Hospital());
        Department department = new Department();
        department.setHospitalId(1L);

        assertThat(service.create(department)).isSameAs(department);
        verify(departmentMapper).insert(department);
    }

    @Test
    void createDepartmentRejectsWhenHospitalMissing() {
        when(hospitalMapper.selectById(99L)).thenReturn(null);
        Department department = new Department();
        department.setHospitalId(99L);

        assertThatThrownBy(() -> service.create(department))
                .isInstanceOf(ApiException.class)
                .hasMessage("医院不存在");
        verify(departmentMapper, never()).insert(any(Department.class));
    }

    private DepartmentAdminService service() {
        DepartmentAdminService service = new DepartmentAdminService(hospitalMapper);
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", departmentMapper);
        return service;
    }
}

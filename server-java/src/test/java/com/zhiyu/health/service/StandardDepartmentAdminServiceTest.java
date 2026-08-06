package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.StandardDepartment;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.StandardDepartmentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** B 端平台标准科室管理（票 49）：存在实际科室映射时删除 409 */
class StandardDepartmentAdminServiceTest {

    private final StandardDepartmentMapper standardDepartmentMapper = mock(StandardDepartmentMapper.class);
    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final StandardDepartmentAdminService service = service();

    @Test
    void deleteRejects409WhenDepartmentsMapped() {
        StandardDepartment standardDepartment = new StandardDepartment();
        standardDepartment.setId(1L);
        when(standardDepartmentMapper.selectById(1L)).thenReturn(standardDepartment);
        when(departmentMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(ApiException.class)
                .hasMessage("标准科室已被实际科室映射，无法删除");
        verify(standardDepartmentMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteRejects404WhenMissing() {
        when(standardDepartmentMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ApiException.class)
                .hasMessage("标准科室不存在");
        verify(standardDepartmentMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteProceedsWhenUnmapped() {
        StandardDepartment standardDepartment = new StandardDepartment();
        standardDepartment.setId(1L);
        when(standardDepartmentMapper.selectById(1L)).thenReturn(standardDepartment);
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        when(standardDepartmentMapper.deleteById(1L)).thenReturn(1);

        service.delete(1L);
        verify(standardDepartmentMapper).deleteById(1L);
    }

    private StandardDepartmentAdminService service() {
        StandardDepartmentAdminService service = new StandardDepartmentAdminService(departmentMapper);
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", standardDepartmentMapper);
        return service;
    }
}

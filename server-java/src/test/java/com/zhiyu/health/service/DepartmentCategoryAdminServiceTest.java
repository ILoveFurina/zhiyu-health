package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.DepartmentCategory;
import com.zhiyu.health.entity.HospitalCampus;
import com.zhiyu.health.mapper.DepartmentCategoryMapper;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.HospitalCampusMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** B 端院区科室分类管理：院区外键 404；存在科室删除 409 */
class DepartmentCategoryAdminServiceTest {

    private final DepartmentCategoryMapper categoryMapper = mock(DepartmentCategoryMapper.class);
    private final HospitalCampusMapper hospitalCampusMapper = mock(HospitalCampusMapper.class);
    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final DepartmentCategoryAdminService service = service();

    @Test
    void createCategoryRejectsWhenCampusMissing() {
        when(hospitalCampusMapper.selectById(99L)).thenReturn(null);
        DepartmentCategory category = new DepartmentCategory();
        category.setCampusId(99L);

        assertThatThrownBy(() -> service.create(category))
                .isInstanceOf(ApiException.class)
                .hasMessage("院区不存在");
        verify(categoryMapper, never()).insert(any(DepartmentCategory.class));
    }

    @Test
    void createCategoryInsertsWhenCampusExists() {
        when(hospitalCampusMapper.selectById(11L)).thenReturn(new HospitalCampus());
        DepartmentCategory category = new DepartmentCategory();
        category.setCampusId(11L);

        service.create(category);
        verify(categoryMapper).insert(category);
    }

    @Test
    void deleteCategoryRejects409WhenDepartmentsExist() {
        DepartmentCategory category = new DepartmentCategory();
        category.setId(11L);
        when(categoryMapper.selectById(11L)).thenReturn(category);
        when(departmentMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(11L))
                .isInstanceOf(ApiException.class)
                .hasMessage("科室分类下存在科室，无法删除");
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteCategoryRejects404WhenMissing() {
        when(categoryMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ApiException.class)
                .hasMessage("科室分类不存在");
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    private DepartmentCategoryAdminService service() {
        DepartmentCategoryAdminService service = new DepartmentCategoryAdminService(hospitalCampusMapper, departmentMapper);
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", categoryMapper);
        return service;
    }
}

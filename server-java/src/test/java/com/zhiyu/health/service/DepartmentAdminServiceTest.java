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
import com.zhiyu.health.entity.DepartmentCategory;
import com.zhiyu.health.entity.HospitalCampus;
import com.zhiyu.health.entity.StandardDepartment;
import com.zhiyu.health.mapper.DepartmentCategoryMapper;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.HospitalCampusMapper;
import com.zhiyu.health.mapper.StandardDepartmentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** B 端科室管理（票 49）：三外键 404、院区与分类同医院 400、存在医生删除 409 */
class DepartmentAdminServiceTest {

    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final HospitalCampusMapper hospitalCampusMapper = mock(HospitalCampusMapper.class);
    private final DepartmentCategoryMapper departmentCategoryMapper = mock(DepartmentCategoryMapper.class);
    private final StandardDepartmentMapper standardDepartmentMapper = mock(StandardDepartmentMapper.class);
    private final DoctorMapper doctorMapper = mock(DoctorMapper.class);
    private final DepartmentAdminService service = service();

    @Test
    void createDepartmentInsertsWhenAllReferencesExist() {
        stubValidReferences(1L, 1L);
        Department department = demoDepartment();

        assertThat(service.create(department)).isSameAs(department);
        verify(departmentMapper).insert(department);
    }

    @Test
    void createDepartmentRejectsWhenCampusMissing() {
        when(hospitalCampusMapper.selectById(11L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(demoDepartment()))
                .isInstanceOf(ApiException.class)
                .hasMessage("院区不存在");
        verify(departmentMapper, never()).insert(any(Department.class));
    }

    @Test
    void createDepartmentRejectsWhenCategoryMissing() {
        HospitalCampus campus = campus(1L);
        when(hospitalCampusMapper.selectById(11L)).thenReturn(campus);
        when(departmentCategoryMapper.selectById(11L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(demoDepartment()))
                .isInstanceOf(ApiException.class)
                .hasMessage("科室分类不存在");
        verify(departmentMapper, never()).insert(any(Department.class));
    }

    @Test
    void createDepartmentRejectsWhenStandardDepartmentMissing() {
        when(hospitalCampusMapper.selectById(11L)).thenReturn(campus(1L));
        when(departmentCategoryMapper.selectById(11L)).thenReturn(category(1L));
        when(standardDepartmentMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(demoDepartment()))
                .isInstanceOf(ApiException.class)
                .hasMessage("标准科室不存在");
        verify(departmentMapper, never()).insert(any(Department.class));
    }

    @Test
    void createDepartmentRejects400WhenCampusAndCategoryBelongToDifferentHospitals() {
        when(hospitalCampusMapper.selectById(11L)).thenReturn(campus(1L));
        when(departmentCategoryMapper.selectById(11L)).thenReturn(category(2L));
        when(standardDepartmentMapper.selectById(1L)).thenReturn(new StandardDepartment());

        assertThatThrownBy(() -> service.create(demoDepartment()))
                .isInstanceOf(ApiException.class)
                .hasMessage("院区与科室分类不属于同一医院");
        verify(departmentMapper, never()).insert(any(Department.class));
    }

    @Test
    void deleteDepartmentRejects409WhenDoctorsExist() {
        Department department = demoDepartment();
        when(departmentMapper.selectById(1L)).thenReturn(department);
        when(doctorMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(ApiException.class)
                .hasMessage("科室下存在医生，无法删除");
        verify(departmentMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteDepartmentRejects404WhenMissing() {
        when(departmentMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ApiException.class)
                .hasMessage("科室不存在");
        verify(departmentMapper, never()).deleteById(any(Long.class));
    }

    private void stubValidReferences(long hospitalId, long categoryHospitalId) {
        when(hospitalCampusMapper.selectById(11L)).thenReturn(campus(hospitalId));
        when(departmentCategoryMapper.selectById(11L)).thenReturn(category(categoryHospitalId));
        when(standardDepartmentMapper.selectById(1L)).thenReturn(new StandardDepartment());
    }

    private HospitalCampus campus(long hospitalId) {
        HospitalCampus campus = new HospitalCampus();
        campus.setId(11L);
        campus.setHospitalId(hospitalId);
        return campus;
    }

    private DepartmentCategory category(long hospitalId) {
        DepartmentCategory category = new DepartmentCategory();
        category.setId(11L);
        category.setHospitalId(hospitalId);
        return category;
    }

    private Department demoDepartment() {
        Department department = new Department();
        department.setId(1L);
        department.setCampusId(11L);
        department.setCategoryId(11L);
        department.setStandardDepartmentId(1L);
        department.setName("心血管内科");
        return department;
    }

    private DepartmentAdminService service() {
        DepartmentAdminService service = new DepartmentAdminService(
                hospitalCampusMapper, departmentCategoryMapper, standardDepartmentMapper, doctorMapper);
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", departmentMapper);
        return service;
    }
}

package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.organization.Hospital;
import com.zhiyu.health.entity.organization.HospitalCampus;
import com.zhiyu.health.mapper.organization.DepartmentMapper;
import com.zhiyu.health.mapper.organization.HospitalCampusMapper;
import com.zhiyu.health.mapper.organization.HospitalMapper;
import com.zhiyu.health.mapper.pharmacy.CampusPharmacyMapper;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.service.organization.CampusAdminService;
import com.zhiyu.health.service.pharmacy.CampusPharmacyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** B 端院区管理（票 49）：医院外键 404；存在科室删除 409 */
class CampusAdminServiceTest {

    private final HospitalCampusMapper campusMapper = mock(HospitalCampusMapper.class);
    private final HospitalMapper hospitalMapper = mock(HospitalMapper.class);
    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final CampusPharmacyService campusPharmacyService = mock(CampusPharmacyService.class);
    private final CampusPharmacyMapper campusPharmacyMapper = mock(CampusPharmacyMapper.class);
    private final PharmacyMedicationMapper pharmacyMedicationMapper = mock(PharmacyMedicationMapper.class);
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
    void createCampusInsertsPharmacyInSameFlow() {
        // 票 88：院区创建同事务自动建院区药房（一院区一药房），不设独立新增入口
        when(hospitalMapper.selectById(1L)).thenReturn(new Hospital());
        HospitalCampus campus = new HospitalCampus();
        campus.setHospitalId(1L);

        service.create(campus);
        verify(campusMapper).insert(campus);
        verify(campusPharmacyService).createForCampus(campus);
    }

    @Test
    void deleteCampusRemovesPharmacyRowsBeforeCampus() {
        // 院区删除同事务清理药房药品关系与药房（先子后父），无历史引用时可整体删净
        HospitalCampus campus = new HospitalCampus();
        campus.setId(11L);
        when(campusMapper.selectById(11L)).thenReturn(campus);
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        com.zhiyu.health.entity.pharmacy.CampusPharmacy pharmacy =
                new com.zhiyu.health.entity.pharmacy.CampusPharmacy();
        pharmacy.setId(71L);
        when(campusPharmacyMapper.selectByCampusId(11L)).thenReturn(pharmacy);

        service.delete(11L);

        verify(pharmacyMedicationMapper).delete(any());
        verify(campusPharmacyMapper).deleteById(71L);
        verify(campusMapper).deleteById(11L);
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
        CampusAdminService service = new CampusAdminService(
                hospitalMapper,
                departmentMapper,
                campusPharmacyService,
                campusPharmacyMapper,
                pharmacyMedicationMapper);
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", campusMapper);
        return service;
    }
}

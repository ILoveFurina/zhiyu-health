package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.mapper.HospitalCampusMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** B 端医院管理：删除缺失记录抛 404，存在院区抛 409 且不落删除（票 49 全链限制删除） */
class HospitalAdminServiceTest {

    private final HospitalMapper hospitalMapper = mock(HospitalMapper.class);
    private final HospitalCampusMapper hospitalCampusMapper = mock(HospitalCampusMapper.class);
    private final HospitalAdminService service = service();

    @Test
    void deleteHospitalRejectsWhenMissing() {
        when(hospitalMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ApiException.class)
                .hasMessage("医院不存在");
        verify(hospitalMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteHospitalRejects409WhenCampusesExist() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        when(hospitalMapper.selectById(1L)).thenReturn(hospital);
        when(hospitalCampusMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(ApiException.class)
                .hasMessage("医院下存在院区，无法删除");
        verify(hospitalMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteHospitalProceedsWhenNoCampuses() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        when(hospitalMapper.selectById(1L)).thenReturn(hospital);
        when(hospitalCampusMapper.selectCount(any())).thenReturn(0L);
        when(hospitalMapper.deleteById(1L)).thenReturn(1);

        service.delete(1L);
        verify(hospitalMapper).deleteById(1L);
    }

    private HospitalAdminService service() {
        HospitalAdminService service = new HospitalAdminService(hospitalCampusMapper);
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", hospitalMapper);
        return service;
    }
}

package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.mapper.HospitalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B 端医院管理：删除缺失记录抛 404（ApiExceptionHandler 统一出口） */
class HospitalAdminServiceTest {

    private final HospitalMapper hospitalMapper = mock(HospitalMapper.class);
    private final HospitalAdminService service = service();

    @Test
    void deleteHospitalRejectsWhenNothingDeleted() {
        when(hospitalMapper.deleteById(99L)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ApiException.class)
                .hasMessage("医院不存在");
    }

    private HospitalAdminService service() {
        HospitalAdminService service = new HospitalAdminService();
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", hospitalMapper);
        return service;
    }
}

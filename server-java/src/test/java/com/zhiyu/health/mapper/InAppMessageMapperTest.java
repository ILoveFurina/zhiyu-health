package com.zhiyu.health.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/** 票 60：随访延迟可见过滤钉在 mapper SQL 上——延迟消息（visible_at 在未来）对患者不可见，即时消息不受影响。 */
class InAppMessageMapperTest {

    @Test
    void selectForPatientFiltersOutDelayedMessages() throws Exception {
        Select select = InAppMessageMapper.class
                .getMethod("selectForPatient", long.class)
                .getAnnotation(Select.class);

        assertThat(select.value()).hasSize(1);
        assertThat(select.value()[0]).contains("visible_at <= now()");
    }
}

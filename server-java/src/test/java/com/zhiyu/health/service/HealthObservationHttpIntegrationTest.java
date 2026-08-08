package com.zhiyu.health.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.controller.c.HealthObservationController;
import com.zhiyu.health.entity.HealthObservation;
import com.zhiyu.health.mapper.HealthObservationMapper;
import com.zhiyu.health.service.mapping.HealthObservationDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/** 健康观测核验操作 HTTP seam：confirm/correct/reject 主链路与归属隔离。 */
class HealthObservationHttpIntegrationTest {

    private final HealthObservationMapper observationMapper = mock(HealthObservationMapper.class);
    private final HealthObservationService service = new HealthObservationService(
            observationMapper,
            TestContracts.instance(),
            TestDisclaimers.instance(),
            Mappers.getMapper(HealthObservationDtoMapper.class));
    private final MockMvc mvc = standaloneSetup(new HealthObservationController(
                    service,
                    Mappers.getMapper(com.zhiyu.health.controller.c.mapping.HealthObservationInputMapper.class)))
            .setControllerAdvice(new ApiExceptionHandler())
            // standalone MockMvc 默认转换器不带 JavaTimeModule，与生产 Spring Boot 出口对齐为 ISO 文本
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
            .build();

    @Test
    void confirmReturnsConfirmedObservation() throws Exception {
        when(observationMapper.selectOwned(7L, 12L)).thenReturn(observation(7L, "UNVERIFIED", "REPORT_AI"));
        when(observationMapper.confirm(7L, "USER_CONFIRMED", "UNVERIFIED")).thenReturn(1);
        when(observationMapper.selectById(7L)).thenReturn(observation(7L, "USER_CONFIRMED", "REPORT_AI"));

        mvc.perform(post("/api/c/health-observations/7/confirm").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.verification_status").value("USER_CONFIRMED"))
                .andExpect(jsonPath("$.verification_label").value("报告提取 · 已确认"));
    }

    @Test
    void correctReturnsAppendedUserCorrection() throws Exception {
        when(observationMapper.selectOwned(8L, 12L)).thenReturn(observation(8L, "UNVERIFIED", "REPORT_AI"));
        when(observationMapper.supersede(8L, "SUPERSEDED", "UNVERIFIED", "USER_CONFIRMED"))
                .thenReturn(1);
        when(observationMapper.insert(any(HealthObservation.class))).thenAnswer(invocation -> {
            invocation.<HealthObservation>getArgument(0).setId(101L);
            return 1;
        });

        mvc.perform(post("/api/c/health-observations/8/correct")
                        .requestAttr("authSubject", "12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"5.6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_type").value("USER_CORRECTION"))
                .andExpect(jsonPath("$.source_label").value("患者纠错"))
                .andExpect(jsonPath("$.verification_status").value("USER_CONFIRMED"))
                .andExpect(jsonPath("$.display_value").value("5.6"))
                .andExpect(jsonPath("$.observed_on").value("2026-05-20"));
    }

    @Test
    void rejectReturnsTerminalRejectedObservation() throws Exception {
        when(observationMapper.selectOwned(9L, 12L)).thenReturn(observation(9L, "UNVERIFIED", "REPORT_AI"));
        when(observationMapper.reject(9L, "REJECTED", "UNVERIFIED", "USER_CONFIRMED"))
                .thenReturn(1);
        when(observationMapper.selectById(9L)).thenReturn(observation(9L, "REJECTED", "REPORT_AI"));

        mvc.perform(post("/api/c/health-observations/9/reject").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification_status").value("REJECTED"))
                .andExpect(jsonPath("$.verification_label").value("已排除"));
    }

    @Test
    void otherPatientsObservationIsNotFound() throws Exception {
        when(observationMapper.selectOwned(99L, 12L)).thenReturn(null);

        mvc.perform(post("/api/c/health-observations/99/confirm").requestAttr("authSubject", "12"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/c/health-observations/99/correct")
                        .requestAttr("authSubject", "12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"5.6\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/c/health-observations/99/reject").requestAttr("authSubject", "12"))
                .andExpect(status().isNotFound());
    }

    private HealthObservation observation(long id, String status, String sourceType) {
        HealthObservation observation = new HealthObservation();
        observation.setId(id);
        observation.setHealthProfileId(31L);
        observation.setReportInterpretationId(41L);
        observation.setMetricCode("FASTING_GLUCOSE");
        observation.setValueNumeric(new BigDecimal("5.3"));
        observation.setUnit("mmol/L");
        observation.setReferenceRange("3.9-6.1");
        observation.setObservedOn(LocalDate.parse("2026-05-20"));
        observation.setSourceType(sourceType);
        observation.setVerificationStatus(status);
        observation.setCurrent(true);
        return observation;
    }
}

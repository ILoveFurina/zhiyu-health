package com.zhiyu.health.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.controller.patient.health.HealthProfileController;
import com.zhiyu.health.controller.patient.health.mapping.HealthProfileInputMapper;
import com.zhiyu.health.entity.health.HealthObservation;
import com.zhiyu.health.entity.health.HealthProfile;
import com.zhiyu.health.entity.health.ReportInterpretation;
import com.zhiyu.health.mapper.health.HealthObservationMapper;
import com.zhiyu.health.mapper.health.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.health.HealthProfileMapper;
import com.zhiyu.health.mapper.health.ReportInterpretationMapper;
import com.zhiyu.health.service.health.HealthObservationService;
import com.zhiyu.health.service.health.HealthProfileService;
import com.zhiyu.health.service.health.mapping.HealthObservationDtoMapper;
import com.zhiyu.health.service.health.mapping.HealthProfileDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/** 档案概要与单指标观测 HTTP seam：有效投影、趋势门槛、免责声明与归属隔离。 */
class HealthProfileOverviewHttpIntegrationTest {

    private final HealthProfileMapper profileMapper = mock(HealthProfileMapper.class);
    private final HealthProfileAllergyMapper allergyMapper = mock(HealthProfileAllergyMapper.class);
    private final HealthObservationMapper observationMapper = mock(HealthObservationMapper.class);
    private final ReportInterpretationMapper reportMapper = mock(ReportInterpretationMapper.class);
    private final HealthObservationService observationService = new HealthObservationService(
            observationMapper,
            TestContracts.instance(),
            TestDisclaimers.instance(),
            Mappers.getMapper(HealthObservationDtoMapper.class));
    private final HealthProfileService service = new HealthProfileService(
            profileMapper,
            allergyMapper,
            Mappers.getMapper(HealthProfileDtoMapper.class),
            observationService,
            reportMapper,
            TestContracts.instance(),
            TestDisclaimers.instance(),
            new ObjectMapper());
    private final MockMvc mvc = standaloneSetup(
                    new HealthProfileController(service, mock(HealthProfileInputMapper.class)))
            .setControllerAdvice(new ApiExceptionHandler())
            // standalone MockMvc 默认转换器不带 JavaTimeModule，与生产 Spring Boot 出口对齐为 ISO 文本
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
            .build();

    @Test
    void overviewProjectsEffectiveObservationsWithDisclaimerAndTrendGate() throws Exception {
        HealthProfile profile = profile(31L, 12L);
        when(profileMapper.selectOwned(31L, 12L)).thenReturn(profile);
        when(allergyMapper.selectAllergens(31L)).thenReturn(List.of("青霉素"));
        when(observationMapper.selectEffective(12L, 31L, "UNVERIFIED", "USER_CONFIRMED"))
                .thenReturn(List.of(
                        numericObservation(1L, "WEIGHT", "58.5", "kg", "2026-02-18", "UNVERIFIED"),
                        numericObservation(2L, "WEIGHT", "57.8", "kg", "2026-05-20", "USER_CONFIRMED"),
                        numericObservation(3L, "FASTING_GLUCOSE", "5.3", "mmol/L", "2026-05-20", "UNVERIFIED"),
                        bloodObservation(4L, "A", "2026-05-20")));
        when(reportMapper.selectSucceededByProfile(eq(12L), eq(31L), anyInt()))
                .thenReturn(List.of(succeededReport(41L)));

        mvc.perform(get("/api/c/health-profiles/31/overview").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.display_name").value("本人"))
                .andExpect(jsonPath("$.allergies[0]").value("青霉素"))
                .andExpect(jsonPath("$.disclaimer").value("仅供参考，不替代医生诊断"))
                // categorical 只放血型类最新有效值，展示值取契约中文文案
                .andExpect(jsonPath("$.categorical.length()").value(1))
                .andExpect(jsonPath("$.categorical[0].metric_code").value("ABO_BLOOD_TYPE"))
                .andExpect(jsonPath("$.categorical[0].display_value").value("A 型"))
                // 数值指标卡：体重两条有效观测 → 趋势非空且按检查日升序
                .andExpect(jsonPath("$.metrics.length()").value(2))
                .andExpect(jsonPath("$.metrics[0].metric_code").value("WEIGHT"))
                .andExpect(jsonPath("$.metrics[0].latest.observation_id").value(2))
                .andExpect(jsonPath("$.metrics[0].trend.length()").value(2))
                .andExpect(jsonPath("$.metrics[0].trend[0].observed_on").value("2026-02-18"))
                .andExpect(jsonPath("$.metrics[0].trend[1].observed_on").value("2026-05-20"))
                // 单条观测的指标 trend 为空数组（≥2 才非空）
                .andExpect(jsonPath("$.metrics[1].metric_code").value("FASTING_GLUCOSE"))
                .andExpect(jsonPath("$.metrics[1].trend.length()").value(0))
                // 最近报告：attention_count 为 red/yellow 项数，exam_date 取采样日
                .andExpect(jsonPath("$.recent_reports[0].id").value(41))
                .andExpect(jsonPath("$.recent_reports[0].attention_count").value(2))
                .andExpect(jsonPath("$.recent_reports[0].exam_date").value("2026-02-18"));
        // 有效投影只读 UNVERIFIED/USER_CONFIRMED（REJECTED/SUPERSEDED 由 SQL 排除，此处钉参数）
        verify(observationMapper).selectEffective(12L, 31L, "UNVERIFIED", "USER_CONFIRMED");
    }

    @Test
    void overviewOfOtherPatientsProfileIsNotFound() throws Exception {
        when(profileMapper.selectOwned(99L, 12L)).thenReturn(null);

        mvc.perform(get("/api/c/health-profiles/99/overview").requestAttr("authSubject", "12"))
                .andExpect(status().isNotFound());
    }

    @Test
    void metricObservationsListEffectiveRowsAscendingWithDisclaimer() throws Exception {
        when(profileMapper.selectOwned(31L, 12L)).thenReturn(profile(31L, 12L));
        when(observationMapper.selectEffective(12L, 31L, "UNVERIFIED", "USER_CONFIRMED"))
                .thenReturn(List.of(
                        numericObservation(1L, "WEIGHT", "58.5", "kg", "2026-02-18", "UNVERIFIED"),
                        numericObservation(3L, "FASTING_GLUCOSE", "5.1", "mmol/L", "2026-02-18", "USER_CONFIRMED"),
                        numericObservation(5L, "FASTING_GLUCOSE", "5.3", "mmol/L", "2026-05-20", "UNVERIFIED")));

        mvc.perform(get("/api/c/health-profiles/31/observations")
                        .param("metric_code", "FASTING_GLUCOSE")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric_code").value("FASTING_GLUCOSE"))
                .andExpect(jsonPath("$.name_zh").value("空腹血糖"))
                .andExpect(jsonPath("$.value_type").value("NUMERIC"))
                .andExpect(jsonPath("$.unit").value("mmol/L"))
                .andExpect(jsonPath("$.observations.length()").value(2))
                .andExpect(jsonPath("$.observations[0].observed_on").value("2026-02-18"))
                .andExpect(jsonPath("$.observations[0].verification_label").value("报告提取 · 已确认"))
                .andExpect(jsonPath("$.observations[1].display_value").value("5.3"))
                .andExpect(jsonPath("$.disclaimer").value("仅供参考，不替代医生诊断"));
    }

    @Test
    void metricObservationsRejectsMetricOutsideWhitelist() throws Exception {
        when(profileMapper.selectOwned(31L, 12L)).thenReturn(profile(31L, 12L));

        mvc.perform(get("/api/c/health-profiles/31/observations")
                        .param("metric_code", "URIC_ACID")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isUnprocessableEntity());
    }

    private HealthProfile profile(long id, long patientId) {
        HealthProfile profile = new HealthProfile();
        profile.setId(id);
        profile.setPatientId(patientId);
        profile.setDisplayName("本人");
        profile.setGender("MALE");
        profile.setBirthDate(LocalDate.parse("1990-01-01"));
        profile.setRelationship("SELF");
        return profile;
    }

    private HealthObservation numericObservation(
            long id, String metric, String value, String unit, String observedOn, String status) {
        HealthObservation observation = new HealthObservation();
        observation.setId(id);
        observation.setHealthProfileId(31L);
        observation.setReportInterpretationId(41L);
        observation.setMetricCode(metric);
        observation.setValueNumeric(new BigDecimal(value));
        observation.setUnit(unit);
        observation.setObservedOn(LocalDate.parse(observedOn));
        observation.setSourceType("REPORT_AI");
        observation.setVerificationStatus(status);
        observation.setCurrent(true);
        return observation;
    }

    private HealthObservation bloodObservation(long id, String category, String observedOn) {
        HealthObservation observation = new HealthObservation();
        observation.setId(id);
        observation.setHealthProfileId(31L);
        observation.setReportInterpretationId(41L);
        observation.setMetricCode("ABO_BLOOD_TYPE");
        observation.setValueCategory(category);
        observation.setObservedOn(LocalDate.parse(observedOn));
        observation.setSourceType("REPORT_AI");
        observation.setVerificationStatus("UNVERIFIED");
        observation.setCurrent(true);
        return observation;
    }

    private ReportInterpretation succeededReport(long id) {
        ReportInterpretation report = new ReportInterpretation();
        report.setId(id);
        report.setPatientId(12L);
        report.setHealthProfileId(31L);
        report.setFileName("演示体检报告.png");
        report.setStatus("SUCCEEDED");
        report.setResultJson(
                """
                {"summary":"两项需关注","sample_or_exam_date":"2026-02-18","report_date":"2026-02-20",
                 "items":[
                   {"name":"总胆固醇","value":"5.8","priority":"red"},
                   {"name":"空腹血糖","value":"6.2","priority":"yellow"},
                   {"name":"体重","value":"58.5","priority":"green"}
                 ],"actions":[],"unreadable":[]}
                """);
        return report;
    }
}

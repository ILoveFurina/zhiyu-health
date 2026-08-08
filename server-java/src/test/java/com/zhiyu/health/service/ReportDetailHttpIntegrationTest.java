package com.zhiyu.health.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.controller.c.ReportInterpretationController;
import com.zhiyu.health.entity.HealthObservation;
import com.zhiyu.health.entity.ReportInterpretation;
import com.zhiyu.health.mapper.HealthObservationMapper;
import com.zhiyu.health.service.mapping.ReportInterpretationDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.MockMvc;

/** 报告详情 HTTP seam（票 61）：逐项沉淀状态读取时推导、归属隔离与免责声明兜底。 */
class ReportDetailHttpIntegrationTest {

    private final ReportInterpretationPersistence persistence = mock(ReportInterpretationPersistence.class);
    private final HealthObservationMapper observationMapper = mock(HealthObservationMapper.class);
    private final HealthObservationService observationService = new HealthObservationService(
            observationMapper,
            TestContracts.instance(),
            TestDisclaimers.instance(),
            Mappers.getMapper(com.zhiyu.health.service.mapping.HealthObservationDtoMapper.class));
    private final ReportInterpretationService service = new ReportInterpretationService(
            persistence,
            mock(AgentClient.class),
            new ObjectMapper(),
            mock(ReportUploadStagingService.class),
            TestContracts.instance(),
            mock(HealthProfileService.class),
            TestDisclaimers.instance(),
            Mappers.getMapper(ReportInterpretationDtoMapper.class),
            new HealthObservationMapping(TestContracts.instance()),
            observationService);
    private final MockMvc mvc = standaloneSetup(
                    new ReportInterpretationController(service, mock(ReportUploadStagingService.class)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void detailDerivesItemStatesFromMappingAndObservations() throws Exception {
        ReportInterpretation report = report(
                41L,
                12L,
                """
                {"summary":"均在参考范围内","sample_or_exam_date":"2026-02-18","report_date":"2026-02-20",
                 "items":[
                   {"name":"身高","value":"165","unit":"cm","reference_range":"无","priority":"green","page":1},
                   {"name":"血压","value":"118/76","unit":"mmHg","reference_range":"90-140/60-90","priority":"green","page":1},
                   {"name":"空腹血糖","value":"5.1","unit":"mmol/L","reference_range":"3.9-6.1","priority":"green","page":1},
                   {"name":"丙氨酸氨基转移酶","value":"18","unit":"U/L","reference_range":"7-40","priority":"green","page":1}
                 ],"actions":[],"unreadable":[]}
                """);
        when(persistence.findOwned(12L, 41L)).thenReturn(report);
        when(observationMapper.selectByReport(41L))
                .thenReturn(List.of(
                        observation(1L, "HEIGHT", "UNVERIFIED"),
                        observation(2L, "SYSTOLIC_BP", "USER_CONFIRMED"),
                        observation(3L, "DIASTOLIC_BP", "UNVERIFIED")));

        mvc.perform(get("/api/c/report-interpretations/41").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(41))
                .andExpect(jsonPath("$.health_profile_id").value(31))
                .andExpect(jsonPath("$.sample_or_exam_date").value("2026-02-18"))
                .andExpect(jsonPath("$.report_date").value("2026-02-20"))
                .andExpect(jsonPath("$.disclaimer").value("仅供参考，不替代医生诊断"))
                // 已沉淀待核验：观测行存在且 UNVERIFIED
                .andExpect(jsonPath("$.items[0].item_state").value("DEPOSITED_UNVERIFIED"))
                .andExpect(jsonPath("$.items[0].item_state_label").value("已沉淀 · 待核验"))
                .andExpect(jsonPath("$.items[0].observation_ids[0]").value(1))
                // 血压组合项拆两条：任一待核验则整项暴露待核验
                .andExpect(jsonPath("$.items[1].item_state").value("DEPOSITED_UNVERIFIED"))
                .andExpect(jsonPath("$.items[1].observation_ids.length()").value(2))
                // 映射出候选但无观测行：同日槽位已有档案记录
                .andExpect(jsonPath("$.items[2].item_state").value("DUPLICATE_SLOT"))
                .andExpect(jsonPath("$.items[2].observation_ids.length()").value(0))
                // 非白名单项：UNMAPPED 且无观测
                .andExpect(jsonPath("$.items[3].item_state").value("UNMAPPED"))
                .andExpect(jsonPath("$.items[3].observation_ids.length()").value(0));
    }

    @Test
    void detailMarksWhitelistedItemsNoDateWhenReportLacksDates() throws Exception {
        ReportInterpretation report = report(
                42L,
                12L,
                """
                {"summary":"缺日期","items":[
                   {"name":"体重","value":"57.8","unit":"kg","priority":"green"}
                 ],"actions":[],"unreadable":[]}
                """);
        when(persistence.findOwned(12L, 42L)).thenReturn(report);
        when(observationMapper.selectByReport(42L)).thenReturn(List.of());

        mvc.perform(get("/api/c/report-interpretations/42").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sample_or_exam_date").doesNotExist())
                .andExpect(jsonPath("$.items[0].item_state").value("NO_DATE"))
                .andExpect(jsonPath("$.items[0].item_state_label").value("报告缺少明确检查日期，未沉淀"));
    }

    @Test
    void detailReflectsRejectedAndCorrectionStates() throws Exception {
        ReportInterpretation report = report(
                43L,
                12L,
                """
                {"report_date":"2026-05-20","items":[
                   {"name":"体重","value":"57.8","unit":"kg","priority":"green"},
                   {"name":"空腹血糖","value":"5.3","unit":"mmol/L","priority":"green"}
                 ],"actions":[],"unreadable":[]}
                """);
        when(persistence.findOwned(12L, 43L)).thenReturn(report);
        HealthObservation rejected = observation(11L, "WEIGHT", "REJECTED");
        HealthObservation supersededOld = observation(12L, "FASTING_GLUCOSE", "SUPERSEDED");
        supersededOld.setCurrent(false);
        HealthObservation correction = observation(13L, "FASTING_GLUCOSE", "USER_CONFIRMED");
        correction.setSourceType("USER_CORRECTION");
        when(observationMapper.selectByReport(43L)).thenReturn(List.of(supersededOld, correction, rejected));

        mvc.perform(get("/api/c/report-interpretations/43").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].item_state").value("DEPOSITED_REJECTED"))
                .andExpect(jsonPath("$.items[0].item_state_label").value("已排除"))
                // 纠错后 current 新行（USER_CORRECTION/USER_CONFIRMED）优先，旧 SUPERSEDED 行不主导状态
                .andExpect(jsonPath("$.items[1].item_state").value("DEPOSITED_CONFIRMED"))
                .andExpect(jsonPath("$.items[1].observation_ids[0]").value(13));
    }

    @Test
    void detailOfOtherPatientsReportIsNotFound() throws Exception {
        when(persistence.findOwned(12L, 99L)).thenReturn(null);

        mvc.perform(get("/api/c/report-interpretations/99").requestAttr("authSubject", "12"))
                .andExpect(status().isNotFound());
    }

    private ReportInterpretation report(long id, long patientId, String resultJson) {
        ReportInterpretation report = new ReportInterpretation();
        report.setId(id);
        report.setPatientId(patientId);
        report.setHealthProfileId(31L);
        report.setConversationId(7L);
        report.setFileName("演示体检报告.png");
        report.setFileType("IMAGE");
        report.setPageCount(1);
        report.setStatus("SUCCEEDED");
        report.setResultJson(resultJson);
        report.setCreatedAt(OffsetDateTime.parse("2026-05-22T10:00:00+08:00"));
        return report;
    }

    private HealthObservation observation(long id, String metric, String status) {
        HealthObservation observation = new HealthObservation();
        observation.setId(id);
        observation.setHealthProfileId(31L);
        observation.setReportInterpretationId(41L);
        observation.setMetricCode(metric);
        observation.setValueNumeric(new BigDecimal("5.1"));
        observation.setUnit("mmol/L");
        observation.setObservedOn(LocalDate.parse("2026-02-18"));
        observation.setSourceType("REPORT_AI");
        observation.setVerificationStatus(status);
        observation.setCurrent(true);
        return observation;
    }
}

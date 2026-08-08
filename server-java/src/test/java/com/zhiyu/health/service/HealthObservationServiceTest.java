package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.HealthObservation;
import com.zhiyu.health.mapper.HealthObservationMapper;
import com.zhiyu.health.service.mapping.HealthObservationDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;

/** 健康观测状态机：confirm/correct/reject 的幂等、追加链、终态、归属与并发收敛。 */
class HealthObservationServiceTest {

    private final HealthObservationMapper mapper = mock(HealthObservationMapper.class);
    private final HealthObservationService service = new HealthObservationService(
            mapper,
            TestContracts.instance(),
            TestDisclaimers.instance(),
            Mappers.getMapper(HealthObservationDtoMapper.class));

    @BeforeEach
    void resetStubs() {
        org.mockito.Mockito.reset(mapper);
    }

    @Test
    void confirmAdvancesUnverifiedAndIsIdempotentOnRepeat() {
        HealthObservation unverified = observation(7L, "UNVERIFIED", true, "REPORT_AI");
        HealthObservation confirmed = observation(7L, "USER_CONFIRMED", true, "REPORT_AI");
        when(mapper.selectOwned(7L, 12L)).thenReturn(unverified);
        when(mapper.confirm(7L, "USER_CONFIRMED", "UNVERIFIED")).thenReturn(1);
        when(mapper.selectById(7L)).thenReturn(confirmed);

        HealthObservationService.ObservationView view = service.confirm(12L, 7L);

        assertThat(view.verificationStatus()).isEqualTo("USER_CONFIRMED");
        assertThat(view.verificationLabel()).isEqualTo("报告提取 · 已确认");

        // 重复确认：条件更新 0 行，已是 USER_CONFIRMED → 幂等返回当前观测
        when(mapper.confirm(7L, "USER_CONFIRMED", "UNVERIFIED")).thenReturn(0);
        when(mapper.selectById(7L)).thenReturn(confirmed);
        HealthObservationService.ObservationView repeated = service.confirm(12L, 7L);
        assertThat(repeated.verificationStatus()).isEqualTo("USER_CONFIRMED");
    }

    @Test
    void confirmRejectedConflictAndHistoricalGone() {
        HealthObservation rejected = observation(8L, "REJECTED", true, "REPORT_AI");
        when(mapper.selectOwned(8L, 12L)).thenReturn(rejected);
        when(mapper.confirm(eq(8L), anyString(), anyString())).thenReturn(0);
        when(mapper.selectById(8L)).thenReturn(rejected);

        assertThatThrownBy(() -> service.confirm(12L, 8L))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus())
                        .isEqualTo(409));

        HealthObservation superseded = observation(9L, "SUPERSEDED", false, "REPORT_AI");
        when(mapper.selectOwned(9L, 12L)).thenReturn(superseded);
        when(mapper.confirm(eq(9L), anyString(), anyString())).thenReturn(0);
        when(mapper.selectById(9L)).thenReturn(superseded);

        // 历史版本（current=FALSE）按 404 处理，不泄露旧记录可操作性
        assertThatThrownBy(() -> service.confirm(12L, 9L))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus())
                        .isEqualTo(404));
    }

    @Test
    void correctAppendsUserCorrectionChainAndSupersedesOld() {
        HealthObservation existing = observation(10L, "UNVERIFIED", true, "REPORT_AI");
        when(mapper.selectOwned(10L, 12L)).thenReturn(existing);
        when(mapper.supersede(10L, "SUPERSEDED", "UNVERIFIED", "USER_CONFIRMED"))
                .thenReturn(1);

        HealthObservationService.ObservationView view =
                service.correct(new HealthObservationService.CorrectCommand(12L, 10L, "5.6"));

        ArgumentCaptor<HealthObservation> inserted = ArgumentCaptor.forClass(HealthObservation.class);
        verify(mapper).insert(inserted.capture());
        HealthObservation correction = inserted.getValue();
        // 追加式纠错：新值 + USER_CORRECTION/USER_CONFIRMED/current=TRUE，supersedes 链回旧记录
        assertThat(correction.getValueNumeric()).isEqualByComparingTo(new BigDecimal("5.6"));
        assertThat(correction.getSourceType()).isEqualTo("USER_CORRECTION");
        assertThat(correction.getVerificationStatus()).isEqualTo("USER_CONFIRMED");
        assertThat(correction.getCurrent()).isTrue();
        assertThat(correction.getSupersedesId()).isEqualTo(10L);
        // 日期、指标、单位、参考范围、来源报告不可改：一律沿旧记录
        assertThat(correction.getMetricCode()).isEqualTo(existing.getMetricCode());
        assertThat(correction.getUnit()).isEqualTo(existing.getUnit());
        assertThat(correction.getObservedOn()).isEqualTo(existing.getObservedOn());
        assertThat(correction.getReportInterpretationId()).isEqualTo(existing.getReportInterpretationId());
        assertThat(view.sourceType()).isEqualTo("USER_CORRECTION");
        assertThat(view.displayValue()).isEqualTo("5.6");
    }

    @Test
    void correctChainsOnTopOfEarlierCorrection() {
        HealthObservation earlier = observation(11L, "USER_CONFIRMED", true, "USER_CORRECTION");
        when(mapper.selectOwned(11L, 12L)).thenReturn(earlier);
        when(mapper.supersede(11L, "SUPERSEDED", "UNVERIFIED", "USER_CONFIRMED"))
                .thenReturn(1);

        service.correct(new HealthObservationService.CorrectCommand(12L, 11L, "5.7"));

        ArgumentCaptor<HealthObservation> inserted = ArgumentCaptor.forClass(HealthObservation.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSupersedesId()).isEqualTo(11L);
        assertThat(inserted.getValue().getValueNumeric()).isEqualByComparingTo(new BigDecimal("5.7"));
    }

    @Test
    void correctValidatesValueByContractAndNormalizesCategory() {
        HealthObservation numeric = observation(12L, "UNVERIFIED", true, "REPORT_AI");
        when(mapper.selectOwned(12L, 12L)).thenReturn(numeric);
        assertThatThrownBy(() -> service.correct(new HealthObservationService.CorrectCommand(12L, 12L, "约5.6")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus())
                        .isEqualTo(422));
        verify(mapper, never()).supersede(anyLong(), anyString(), anyString(), anyString());

        HealthObservation blood = observation(13L, "UNVERIFIED", true, "REPORT_AI");
        blood.setMetricCode("ABO_BLOOD_TYPE");
        blood.setValueNumeric(null);
        blood.setValueCategory("A");
        blood.setUnit(null);
        when(mapper.selectOwned(13L, 12L)).thenReturn(blood);
        when(mapper.supersede(13L, "SUPERSEDED", "UNVERIFIED", "USER_CONFIRMED"))
                .thenReturn(1);
        HealthObservationService.ObservationView view =
                service.correct(new HealthObservationService.CorrectCommand(12L, 13L, "B型"));
        assertThat(view.valueCategory()).isEqualTo("B");
        assertThat(view.displayValue()).isEqualTo("B 型");

        assertThatThrownBy(() -> service.correct(new HealthObservationService.CorrectCommand(12L, 13L, "熊猫血")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus())
                        .isEqualTo(422));
    }

    @Test
    void correctConcurrentSupersedeRaceYieldsConflict() {
        HealthObservation existing = observation(14L, "UNVERIFIED", true, "REPORT_AI");
        when(mapper.selectOwned(14L, 12L)).thenReturn(existing);
        // 并发条件更新 0 行：他人已改状态，409 且不追加新记录
        when(mapper.supersede(14L, "SUPERSEDED", "UNVERIFIED", "USER_CONFIRMED"))
                .thenReturn(0);

        assertThatThrownBy(() -> service.correct(new HealthObservationService.CorrectCommand(12L, 14L, "5.6")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus())
                        .isEqualTo(409));
        verify(mapper, never()).insert(any(HealthObservation.class));
    }

    @Test
    void rejectIsTerminalKeepsCurrentAndIsIdempotentOnRepeat() {
        HealthObservation unverified = observation(15L, "UNVERIFIED", true, "REPORT_AI");
        HealthObservation rejected = observation(15L, "REJECTED", true, "REPORT_AI");
        when(mapper.selectOwned(15L, 12L)).thenReturn(unverified);
        when(mapper.reject(15L, "REJECTED", "UNVERIFIED", "USER_CONFIRMED")).thenReturn(1);
        when(mapper.selectById(15L)).thenReturn(rejected);

        HealthObservationService.ObservationView view = service.reject(12L, 15L);

        // 终态 REJECTED 且 current 保持 TRUE（占用每日槽位，阻止重复上传复活）
        assertThat(view.verificationStatus()).isEqualTo("REJECTED");
        assertThat(view.verificationLabel()).isEqualTo("已排除");

        when(mapper.reject(15L, "REJECTED", "UNVERIFIED", "USER_CONFIRMED")).thenReturn(0);
        HealthObservationService.ObservationView repeated = service.reject(12L, 15L);
        assertThat(repeated.verificationStatus()).isEqualTo("REJECTED");
    }

    @Test
    void otherPatientsObservationIsNotFoundForAllOperations() {
        when(mapper.selectOwned(anyLong(), eq(12L))).thenReturn(null);

        assertThatThrownBy(() -> service.confirm(12L, 99L))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus())
                        .isEqualTo(404));
        assertThatThrownBy(() -> service.correct(new HealthObservationService.CorrectCommand(12L, 99L, "5.6")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus())
                        .isEqualTo(404));
        assertThatThrownBy(() -> service.reject(12L, 99L))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus())
                        .isEqualTo(404));
    }

    private HealthObservation observation(long id, String status, boolean current, String sourceType) {
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
        observation.setCurrent(current);
        return observation;
    }
}

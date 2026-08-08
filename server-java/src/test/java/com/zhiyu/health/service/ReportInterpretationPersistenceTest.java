package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.HealthObservation;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.ReportInterpretation;
import com.zhiyu.health.mapper.HealthObservationMapper;
import com.zhiyu.health.mapper.ReportInterpretationMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class ReportInterpretationPersistenceTest {

    @Test
    void reportHistoryDelegatesPatientScopeToOrderedMapperQuery() {
        ReportInterpretationMapper mapper = mock(ReportInterpretationMapper.class);
        when(mapper.selectHistoryByPatient(12L)).thenReturn(List.of(new ReportInterpretation()));
        ReportInterpretationPersistence persistence = new ReportInterpretationPersistence(
                mapper,
                mock(ConversationService.class),
                new ObjectMapper(),
                TestDisclaimers.instance(),
                mock(HealthProfileService.class),
                mock(HealthObservationMapping.class),
                mock(HealthObservationMapper.class),
                TestContracts.instance());

        assertThat(persistence.listForPatient(12L)).hasSize(1);
        verify(mapper).selectHistoryByPatient(12L);
    }

    @Test
    void newReportBelongsToCurrentHealthProfile() {
        ReportInterpretationMapper mapper = mock(ReportInterpretationMapper.class);
        ConversationService conversations = mock(ConversationService.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        HealthProfile profile = new HealthProfile();
        profile.setId(31L);
        when(healthProfiles.requireActive(12L)).thenReturn(profile);
        when(conversations.getOrCreateForPatient(12L, null, "看报告")).thenReturn(new Conversation(7L, 12L, "看报告"));
        when(mapper.insert(any(ReportInterpretation.class))).thenAnswer(invocation -> {
            invocation.<ReportInterpretation>getArgument(0).setId(41L);
            return 1;
        });
        ReportInterpretationPersistence persistence = new ReportInterpretationPersistence(
                mapper,
                conversations,
                new ObjectMapper(),
                TestDisclaimers.instance(),
                healthProfiles,
                mock(HealthObservationMapping.class),
                mock(HealthObservationMapper.class),
                TestContracts.instance());

        ReportInterpretation created = persistence.start(
                12L, null, "req-profile", List.of(new MockMultipartFile("files", "report.png", "image/png", new byte[] {
                    1
                })));

        assertThat(created.getHealthProfileId()).isEqualTo(31L);
        assertThat(created.getPatientId()).isEqualTo(12L);
    }

    @Test
    void succeedDepositsMappedObservationsInSameFlow() {
        ReportInterpretationMapper mapper = mock(ReportInterpretationMapper.class);
        HealthObservationMapper observationMapper = mock(HealthObservationMapper.class);
        ReportInterpretationPersistence persistence = new ReportInterpretationPersistence(
                mapper,
                mock(ConversationService.class),
                new ObjectMapper(),
                TestDisclaimers.instance(),
                mock(HealthProfileService.class),
                new HealthObservationMapping(TestContracts.instance()),
                observationMapper,
                TestContracts.instance());
        ReportInterpretation record = new ReportInterpretation();
        record.setId(41L);
        record.setPatientId(12L);
        record.setHealthProfileId(31L);
        record.setConversationId(7L);
        String resultJson =
                """
                {"summary":"均在参考范围内","sample_or_exam_date":"2026-05-20","report_date":"2026-05-22",
                 "items":[
                   {"name":"体重","value":"57.8","unit":"kg","reference_range":"无","priority":"green"},
                   {"name":"血压","value":"122/78","unit":"mmHg","reference_range":"90-140/60-90","priority":"green"},
                   {"name":"丙氨酸氨基转移酶","value":"20","unit":"U/L","reference_range":"7-40","priority":"green"}
                 ],"actions":[],"unreadable":[]}
                """;
        AgentClient.VisionResponse response = new AgentClient.VisionResponse(null, null, null, 1);

        persistence.succeed(record, response, resultJson, "摘要");

        ArgumentCaptor<HealthObservation> inserted = ArgumentCaptor.forClass(HealthObservation.class);
        verify(observationMapper, org.mockito.Mockito.times(3)).insertIgnoreSlot(inserted.capture());
        List<HealthObservation> observations = inserted.getAllValues();
        assertThat(observations)
                .extracting(HealthObservation::getMetricCode)
                .containsExactly("WEIGHT", "SYSTOLIC_BP", "DIASTOLIC_BP");
        // 观测随报告归属与采样日沉淀，来源/核验/槽位标记固定
        for (HealthObservation observation : observations) {
            assertThat(observation.getHealthProfileId()).isEqualTo(31L);
            assertThat(observation.getReportInterpretationId()).isEqualTo(41L);
            assertThat(observation.getObservedOn()).isEqualTo(LocalDate.parse("2026-05-20"));
            assertThat(observation.getSourceType()).isEqualTo("REPORT_AI");
            assertThat(observation.getVerificationStatus()).isEqualTo("UNVERIFIED");
            assertThat(observation.getCurrent()).isTrue();
        }
        // 非白名单项不沉淀；参考范围 "无" 归一为 null
        assertThat(observations.get(0).getReferenceRange()).isNull();
        assertThat(observations.get(0).getValueNumeric()).isEqualByComparingTo("57.8");
    }

    @Test
    void succeedSkipsDepositWhenReportHasNoDate() {
        ReportInterpretationMapper mapper = mock(ReportInterpretationMapper.class);
        HealthObservationMapper observationMapper = mock(HealthObservationMapper.class);
        ReportInterpretationPersistence persistence = new ReportInterpretationPersistence(
                mapper,
                mock(ConversationService.class),
                new ObjectMapper(),
                TestDisclaimers.instance(),
                mock(HealthProfileService.class),
                new HealthObservationMapping(TestContracts.instance()),
                observationMapper,
                TestContracts.instance());
        ReportInterpretation record = new ReportInterpretation();
        record.setId(42L);
        record.setPatientId(12L);
        record.setHealthProfileId(31L);
        record.setConversationId(7L);
        String resultJson =
                """
                {"summary":"缺日期","items":[{"name":"体重","value":"57.8","unit":"kg","priority":"green"}],
                 "actions":[],"unreadable":[]}
                """;
        AgentClient.VisionResponse response = new AgentClient.VisionResponse(null, null, null, 1);

        persistence.succeed(record, response, resultJson, "摘要");

        // 整份无日期：一条观测都不沉淀，但报告成功落库不受影响
        verify(observationMapper, never()).insertIgnoreSlot(any());
        verify(mapper).updateById(record);
    }
}

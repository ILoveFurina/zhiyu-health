package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.ReportInterpretation;
import com.zhiyu.health.mapper.ReportInterpretationMapper;
import com.zhiyu.health.support.TestDisclaimers;
import java.util.List;
import org.junit.jupiter.api.Test;
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
                mock(HealthProfileService.class));

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
                mapper, conversations, new ObjectMapper(), TestDisclaimers.instance(), healthProfiles);

        ReportInterpretation created = persistence.start(
                12L, null, "req-profile", List.of(new MockMultipartFile("files", "report.png", "image/png", new byte[] {
                    1
                })));

        assertThat(created.getHealthProfileId()).isEqualTo(31L);
        assertThat(created.getPatientId()).isEqualTo(12L);
    }
}

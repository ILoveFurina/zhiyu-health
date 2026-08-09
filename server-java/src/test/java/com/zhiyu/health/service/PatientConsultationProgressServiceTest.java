package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.PreconsultationDraft;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.mapper.chat.PreconsultationDraftMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.service.consultation.PatientConsultationProgressService;
import com.zhiyu.health.service.consultation.mapping.PatientConsultationProgressDtoMapperImpl;
import com.zhiyu.health.service.health.HealthProfileService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatientConsultationProgressServiceTest {

    @Test
    void returnsOnlyStartedDraftsAndActiveConsultationsAcrossProfiles() {
        PreconsultationDraftMapper drafts = mock(PreconsultationDraftMapper.class);
        OnlineConsultationMapper consultations = mock(OnlineConsultationMapper.class);
        HealthProfileService profiles = mock(HealthProfileService.class);
        Contracts contracts = Contracts.load(Contracts.resolveDir());
        PatientConsultationProgressService service = new PatientConsultationProgressService(
                drafts, consultations, profiles, contracts, new PatientConsultationProgressDtoMapperImpl());

        PreconsultationDraft draft = new PreconsultationDraft();
        draft.setId(8L);
        draft.setHealthProfileId(2L);
        draft.setStatus("COLLECTING");
        draft.setUpdatedAt(OffsetDateTime.parse("2026-08-09T10:00:00+08:00"));
        OnlineConsultation active = consultation(9L, 3L, "IN_PROGRESS");
        OnlineConsultation completed = consultation(10L, 3L, "COMPLETED");
        when(drafts.selectStartedActiveByPatient(1L, "COLLECTING", "PENDING_CONFIRM"))
                .thenReturn(List.of(draft));
        when(consultations.selectByPatient(1L)).thenReturn(List.of(active, completed));
        when(profiles.list(1L))
                .thenReturn(List.of(
                        new HealthProfileService.ProfileView(2L, "林小满", "女", null, "SELF", true, List.of()),
                        new HealthProfileService.ProfileView(3L, "林妈妈", "女", null, "MOTHER", false, List.of())));

        List<PatientConsultationProgressService.ProgressItem> result = service.list(1L);

        assertThat(result)
                .extracting(PatientConsultationProgressService.ProgressItem::status)
                .containsExactly("COLLECTING", "IN_PROGRESS");
        assertThat(result)
                .extracting(PatientConsultationProgressService.ProgressItem::healthProfileName)
                .containsExactly("林小满", "林妈妈");
        verify(consultations).expireOverdue("WAITING_DOCTOR", "EXPIRED");
    }

    private OnlineConsultation consultation(long id, long profileId, String status) {
        OnlineConsultation consultation = new OnlineConsultation();
        consultation.setId(id);
        consultation.setHealthProfileId(profileId);
        consultation.setStatus(status);
        consultation.setUpdatedAt(OffsetDateTime.parse("2026-08-09T11:00:00+08:00"));
        return consultation;
    }
}

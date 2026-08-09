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
        // 票 86：时长窗惰性收敛与接诊超时同一入口生效（契约 1800s + duration_expired 文案）
        verify(consultations)
                .expireInProgressOverdue("IN_PROGRESS", "EXPIRED", 1800, "SYSTEM", "text", "问诊时间已到，本次问诊已自动结束");
    }

    // ------------------------------------------------------------------
    // 票 86：处方追踪投影（已完成但处方未终结）
    // ------------------------------------------------------------------

    @Test
    void projectsUnresolvedPrescriptionTrackingForThreeStatuses() {
        PreconsultationDraftMapper drafts = mock(PreconsultationDraftMapper.class);
        OnlineConsultationMapper consultations = mock(OnlineConsultationMapper.class);
        HealthProfileService profiles = mock(HealthProfileService.class);
        PatientConsultationProgressService service = service(drafts, consultations, profiles);

        when(profiles.list(1L))
                .thenReturn(List.of(
                        new HealthProfileService.ProfileView(2L, "林小满", "女", null, "SELF", true, List.of()),
                        new HealthProfileService.ProfileView(3L, "林妈妈", "女", null, "MOTHER", false, List.of()),
                        new HealthProfileService.ProfileView(4L, "林爸爸", "男", null, "FATHER", false, List.of())));
        when(consultations.selectUnresolvedPrescriptionTracking(1L, "COMPLETED", "PENDING", "APPROVED", "REJECTED"))
                .thenReturn(List.of(
                        trackingRow(21L, 2L, 31L, "PENDING", false),
                        trackingRow(22L, 3L, 32L, "APPROVED", false),
                        trackingRow(23L, 4L, 33L, "REJECTED", false)));

        List<PatientConsultationProgressService.ProgressItem> result = service.list(1L);

        assertThat(result).hasSize(3);
        assertThat(result)
                .extracting(PatientConsultationProgressService.ProgressItem::referenceType)
                .containsOnly("PRESCRIPTION");
        // 状态值派生自 prescription-flow 契约；标签同契约 status_labels
        assertThat(result)
                .extracting(
                        PatientConsultationProgressService.ProgressItem::status,
                        PatientConsultationProgressService.ProgressItem::statusLabel)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PENDING", "待审核"),
                        org.assertj.core.groups.Tuple.tuple("APPROVED", "已通过"),
                        org.assertj.core.groups.Tuple.tuple("REJECTED", "已驳回"));
        PatientConsultationProgressService.ProgressItem pending = result.get(0);
        assertThat(pending.referenceId()).isEqualTo(31L);
        assertThat(pending.prescriptionId()).isEqualTo(31L);
        assertThat(pending.onlineConsultationId()).isEqualTo(21L);
        assertThat(pending.healthProfileName()).isEqualTo("林小满");
        assertThat(pending.doctorName()).isEqualTo("林医生");
        assertThat(pending.departmentName()).isEqualTo("呼吸内科");
        assertThat(pending.consultationEndsAt()).isNull();
    }

    @Test
    void approvedPrescriptionWithDrugOrderIsNotProjected() {
        // 已下单的 APPROVED 处方交接给首页药品待支付卡（listDrugOrders），追踪卡不再投影
        PreconsultationDraftMapper drafts = mock(PreconsultationDraftMapper.class);
        OnlineConsultationMapper consultations = mock(OnlineConsultationMapper.class);
        HealthProfileService profiles = mock(HealthProfileService.class);
        PatientConsultationProgressService service = service(drafts, consultations, profiles);

        when(profiles.list(1L)).thenReturn(List.of());
        when(consultations.selectUnresolvedPrescriptionTracking(1L, "COMPLETED", "PENDING", "APPROVED", "REJECTED"))
                .thenReturn(List.of(trackingRow(21L, 2L, 31L, "APPROVED", true)));

        assertThat(service.list(1L)).isEmpty();
    }

    @Test
    void completedConsultationWithoutPrescriptionYieldsNoTrackingItem() {
        // 无处方的 COMPLETED 保持现状消失：追踪查询为空即无投影
        PreconsultationDraftMapper drafts = mock(PreconsultationDraftMapper.class);
        OnlineConsultationMapper consultations = mock(OnlineConsultationMapper.class);
        HealthProfileService profiles = mock(HealthProfileService.class);
        PatientConsultationProgressService service = service(drafts, consultations, profiles);

        when(consultations.selectByPatient(1L)).thenReturn(List.of(consultation(10L, 3L, "COMPLETED")));
        when(profiles.list(1L)).thenReturn(List.of());

        assertThat(service.list(1L)).isEmpty();
        // 覆盖规则（每档案只取最近一次问诊链路）由 SQL MAX(id) 子查询保证；
        // 此处钉住服务透传的状态参数（COMPLETED 链路 + 三种未终结处方态）
        verify(consultations).selectUnresolvedPrescriptionTracking(1L, "COMPLETED", "PENDING", "APPROVED", "REJECTED");
    }

    @Test
    void inProgressConsultationItemCarriesConsultationEndsAt() {
        // 首页进行中卡同步携带倒计时截止时间（accepted_at + 契约时长窗）
        PreconsultationDraftMapper drafts = mock(PreconsultationDraftMapper.class);
        OnlineConsultationMapper consultations = mock(OnlineConsultationMapper.class);
        HealthProfileService profiles = mock(HealthProfileService.class);
        PatientConsultationProgressService service = service(drafts, consultations, profiles);

        OnlineConsultation active = consultation(9L, 3L, "IN_PROGRESS");
        active.setAcceptedAt(OffsetDateTime.parse("2026-08-09T10:30:00+08:00"));
        when(consultations.selectByPatient(1L)).thenReturn(List.of(active));
        when(profiles.list(1L))
                .thenReturn(List.of(
                        new HealthProfileService.ProfileView(3L, "林妈妈", "女", null, "MOTHER", false, List.of())));

        List<PatientConsultationProgressService.ProgressItem> result = service.list(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).onlineConsultationId()).isEqualTo(9L);
        assertThat(result.get(0).consultationEndsAt())
                .isEqualTo(active.getAcceptedAt().plusSeconds(1800).toString());
    }

    private PatientConsultationProgressService service(
            PreconsultationDraftMapper drafts, OnlineConsultationMapper consultations, HealthProfileService profiles) {
        return new PatientConsultationProgressService(
                drafts,
                consultations,
                profiles,
                Contracts.load(Contracts.resolveDir()),
                new PatientConsultationProgressDtoMapperImpl());
    }

    private OnlineConsultationMapper.PrescriptionTrackingRow trackingRow(
            long consultationId, long profileId, long prescriptionId, String status, boolean hasDrugOrder) {
        return new OnlineConsultationMapper.PrescriptionTrackingRow(
                consultationId,
                profileId,
                prescriptionId,
                status,
                "林医生",
                "呼吸内科",
                hasDrugOrder,
                OffsetDateTime.parse("2026-08-09T12:00:00+08:00"));
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

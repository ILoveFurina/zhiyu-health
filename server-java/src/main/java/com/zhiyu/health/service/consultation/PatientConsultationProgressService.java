package com.zhiyu.health.service.consultation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.PreconsultationDraft;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.mapper.chat.PreconsultationDraftMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.service.consultation.mapping.PatientConsultationProgressDtoMapper;
import com.zhiyu.health.service.health.HealthProfileService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 首页在线问诊待办的统一只读投影；空草稿和所有终态都不进入结果。 */
@Service
@RequiredArgsConstructor
public class PatientConsultationProgressService {

    private final PreconsultationDraftMapper drafts;
    private final OnlineConsultationMapper consultations;
    private final HealthProfileService healthProfiles;
    private final Contracts contracts;
    private final PatientConsultationProgressDtoMapper dtoMapper;

    public List<ProgressItem> list(long patientId) {
        Contracts.OnlineConsultation contract = contracts.onlineConsultation();
        String collecting = contract.draftStatuses().get("collecting");
        String pendingConfirm = contract.draftStatuses().get("pending_confirm");
        String waiting = contract.statuses().get("waiting_doctor");
        String inProgress = contract.statuses().get("in_progress");
        consultations.expireOverdue(waiting, contract.statuses().get("expired"));

        Map<Long, HealthProfileService.ProfileView> profiles = healthProfiles.list(patientId).stream()
                .collect(Collectors.toMap(HealthProfileService.ProfileView::id, Function.identity()));
        List<ProgressItem> items = new ArrayList<>();
        for (PreconsultationDraft draft : drafts.selectStartedActiveByPatient(patientId, collecting, pendingConfirm)) {
            HealthProfileService.ProfileView profile = profiles.get(draft.getHealthProfileId());
            items.add(dtoMapper.fromDraft(
                    draft,
                    collecting.equals(draft.getStatus()) ? "预问诊进行中" : "待确认病情摘要",
                    profile == null ? "就诊人" : profile.displayName()));
        }
        for (OnlineConsultation consultation : consultations.selectByPatient(patientId)) {
            if (!waiting.equals(consultation.getStatus()) && !inProgress.equals(consultation.getStatus())) {
                continue;
            }
            HealthProfileService.ProfileView profile = profiles.get(consultation.getHealthProfileId());
            items.add(dtoMapper.fromConsultation(
                    consultation,
                    contract.statusLabels().get(consultation.getStatus()),
                    profile == null ? "就诊人" : profile.displayName()));
        }
        return List.copyOf(items);
    }

    public record ProgressItem(
            @JsonProperty("reference_type") String referenceType,
            @JsonProperty("reference_id") Long referenceId,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("health_profile_id") Long healthProfileId,
            @JsonProperty("health_profile_name") String healthProfileName,
            @JsonProperty("updated_at") String updatedAt) {}
}

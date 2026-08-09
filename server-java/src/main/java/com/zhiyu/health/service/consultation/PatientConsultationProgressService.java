package com.zhiyu.health.service.consultation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.PreconsultationDraft;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.entity.consultation.OnlineConsultationMessage;
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

/** 首页在线问诊待办的统一只读投影；空草稿和所有终态都不进入结果。
 *  票 86 增量：活跃态之外再投影"已完成但处方未终结"的追踪项（每档案只取最近一次问诊链路）。 */
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
        String expired = contract.statuses().get("expired");
        consultations.expireOverdue(waiting, expired);
        // 时长窗惰性收敛（票 86）：与接诊超时同一入口语义，翻转为 EXPIRED 的单子由 SQL 自带系统消息
        consultations.expireInProgressOverdue(
                inProgress,
                expired,
                contract.consultationDurationSeconds(),
                contract.senderTypes().get("system"),
                OnlineConsultationMessage.KIND_TEXT,
                contract.texts().get("duration_expired"));

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
                    profile == null ? "就诊人" : profile.displayName(),
                    consultationEndsAt(contract, consultation, inProgress)));
        }
        items.addAll(prescriptionTracking(patientId, contract, profiles));
        return List.copyOf(items);
    }

    /**
     * 处方追踪（票 86）：COMPLETED 问诊链路的未终结处方投影——PENDING=审核中、APPROVED 未下单=可购药、
     * REJECTED=未通过；APPROVED 已下单交接给首页药品待支付卡（listDrugOrders），不再投影。
     * 每档案只取最近一次问诊链路（SQL 内 MAX(id)），新问诊发起后旧 REJECTED 卡自然消失。
     */
    private List<ProgressItem> prescriptionTracking(
            long patientId,
            Contracts.OnlineConsultation contract,
            Map<Long, HealthProfileService.ProfileView> profiles) {
        Contracts.PrescriptionFlow flow = contracts.prescriptionFlow();
        String pending = flow.statuses().get("pending");
        String approved = flow.statuses().get("approved");
        String rejected = flow.statuses().get("rejected");
        List<ProgressItem> tracking = new ArrayList<>();
        for (OnlineConsultationMapper.PrescriptionTrackingRow row : consultations.selectUnresolvedPrescriptionTracking(
                patientId, contract.statuses().get("completed"), pending, approved, rejected)) {
            if (approved.equals(row.prescriptionStatus()) && row.hasDrugOrder()) {
                continue;
            }
            HealthProfileService.ProfileView profile = profiles.get(row.healthProfileId());
            tracking.add(dtoMapper.fromTracking(
                    row,
                    flow.statusLabels().get(row.prescriptionStatus()),
                    profile == null ? "就诊人" : profile.displayName()));
        }
        return tracking;
    }

    /** 双端倒计时截止时间（票 86）：accepted_at + 契约时长窗，仅进行中单有值。 */
    private String consultationEndsAt(
            Contracts.OnlineConsultation contract, OnlineConsultation consultation, String inProgress) {
        if (!inProgress.equals(consultation.getStatus()) || consultation.getAcceptedAt() == null) {
            return null;
        }
        return consultation
                .getAcceptedAt()
                .plusSeconds(contract.consultationDurationSeconds())
                .toString();
    }

    public record ProgressItem(
            @JsonProperty("reference_type") String referenceType,
            @JsonProperty("reference_id") Long referenceId,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("health_profile_id") Long healthProfileId,
            @JsonProperty("health_profile_name") String healthProfileName,
            @JsonProperty("online_consultation_id") Long onlineConsultationId,
            @JsonProperty("prescription_id") Long prescriptionId,
            @JsonProperty("doctor_name") String doctorName,
            @JsonProperty("department_name") String departmentName,
            @JsonProperty("consultation_ends_at") String consultationEndsAt,
            @JsonProperty("updated_at") String updatedAt) {}
}

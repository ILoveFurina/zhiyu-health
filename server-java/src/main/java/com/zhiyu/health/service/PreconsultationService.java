package com.zhiyu.health.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.OnlineConsultation;
import com.zhiyu.health.entity.PreconsultationDraft;
import com.zhiyu.health.entity.StandardDepartment;
import com.zhiyu.health.mapper.OnlineConsultationMapper;
import com.zhiyu.health.mapper.PreconsultationDraftMapper;
import com.zhiyu.health.mapper.StandardDepartmentMapper;
import com.zhiyu.health.service.mapping.PreconsultationDtoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 预问诊模块（票 55，Spec 0003）：草稿的开始/恢复、归属校验、摘要快照与会话关联。
 * 草稿锁定进入时的激活健康档案；普通 Agent 会话不会触达本模块，预问诊只经明确入口与草稿标识进入。
 */
@Service
@RequiredArgsConstructor
public class PreconsultationService {

    private final PreconsultationDraftMapper draftMapper;
    private final OnlineConsultationMapper consultationMapper;
    private final StandardDepartmentMapper standardDepartmentMapper;
    private final HealthProfileService healthProfiles;
    private final DisclaimerService disclaimers;
    private final Contracts contracts;
    private final PreconsultationDtoMapper dtoMapper;

    /** 开始或恢复预问诊：必须存在激活档案（锁定为本次服务对象）；活跃草稿存在即复用。 */
    public DraftView startOrResume(long patientId) {
        HealthProfile profile = healthProfiles.requireActive(patientId);
        PreconsultationDraft draft =
                draftMapper.selectActive(patientId, profile.getId(), collecting(), pendingConfirm());
        if (draft == null) {
            draft = new PreconsultationDraft();
            draft.setPatientId(patientId);
            draft.setHealthProfileId(profile.getId());
            draft.setStatus(collecting());
            try {
                draftMapper.insert(draft);
            } catch (DuplicateKeyException e) {
                // 并发双进撞上 uq_preconsultation_drafts_active：改查对方已建的活跃草稿（幂等）。
                draft = draftMapper.selectActive(patientId, profile.getId(), collecting(), pendingConfirm());
                if (draft == null) {
                    throw e;
                }
            }
        }
        return toView(draft);
    }

    public DraftView get(long patientId, long draftId) {
        return toView(requireOwned(patientId, draftId));
    }

    /**
     * 对话轮次的草稿绑定校验（可信预问诊模式）：归属、存在性与未提交状态任一不符即 409，
     * 客户端不得伪造草稿标识或借已提交草稿继续获得 preconsultation 场景权限。
     */
    public PreconsultationDraft requireForChat(long patientId, long draftId) {
        PreconsultationDraft draft = draftMapper.selectById(draftId);
        boolean usable = draft != null
                && draft.getPatientId() == patientId
                && !submitted().equals(draft.getStatus());
        if (!usable) {
            throw new ApiException(409, contracts.onlineConsultation().texts().get("scenario_requires_draft"));
        }
        return draft;
    }

    /** 预问诊轮次惰性建会话后回填关联（只补空值，重复调用幂等）。 */
    public void attachConversation(long draftId, long conversationId) {
        draftMapper.attachConversation(draftId, conversationId);
    }

    /**
     * 成功轮次的摘要快照落库：主诉/现病史缺失不构成可用快照，保留上一版；
     * 建议科室必须是标准科室目录内 ID（目录外 ID 丢弃为 NULL——server-py 受控解析，
     * 此处为 server-java 复检，不信任模型自由生成）；免责声明由本端统一兜底，不采用模型回传值。
     */
    public void applySummary(long draftId, JsonNode payload) {
        String chiefComplaint = textOrNull(payload.get("chief_complaint"));
        String presentIllness = textOrNull(payload.get("present_illness"));
        if (chiefComplaint == null || presentIllness == null) {
            return;
        }
        String allergyHistory = textOrNull(payload.get("allergy_history"));
        Long departmentId = null;
        JsonNode departmentNode = payload.get("suggested_standard_department_id");
        if (departmentNode != null && departmentNode.isIntegralNumber()) {
            long candidate = departmentNode.asLong();
            if (standardDepartmentMapper.selectById(candidate) != null) {
                departmentId = candidate;
            }
        }
        // 0 行 = 草稿已被并发提交：快照不可再变属正常竞态，静默保留上一版（调用方保证不连坐对话流）。
        draftMapper.applySummary(
                draftId,
                chiefComplaint,
                presentIllness,
                allergyHistory,
                disclaimers.text(),
                departmentId,
                collecting(),
                pendingConfirm());
    }

    private PreconsultationDraft requireOwned(long patientId, long draftId) {
        PreconsultationDraft draft = draftMapper.selectById(draftId);
        if (draft == null || draft.getPatientId() != patientId) {
            // 归属/不存在一律 404，不区分原因以免泄露存在性（对齐票 27 决策 3）。
            throw new ApiException(404, "预问诊草稿不存在");
        }
        return draft;
    }

    private DraftView toView(PreconsultationDraft draft) {
        DraftSummaryView summary = null;
        if (draft.getSummaryUpdatedAt() != null) {
            String departmentName = null;
            if (draft.getSuggestedStandardDepartmentId() != null) {
                StandardDepartment department =
                        standardDepartmentMapper.selectById(draft.getSuggestedStandardDepartmentId());
                departmentName = department == null ? null : department.getName();
            }
            summary = dtoMapper.toSummaryView(draft, departmentName);
        }
        Long currentConsultationId = null;
        OnlineConsultation latest = consultationMapper.selectLatestByDraftId(draft.getId());
        if (latest != null) {
            currentConsultationId = latest.getId();
        }
        return dtoMapper.toView(
                draft,
                contracts.onlineConsultation().draftStatusLabels().get(draft.getStatus()),
                summary,
                currentConsultationId);
    }

    private String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }

    private String collecting() {
        return contracts.onlineConsultation().draftStatuses().get("collecting");
    }

    private String pendingConfirm() {
        return contracts.onlineConsultation().draftStatuses().get("pending_confirm");
    }

    private String submitted() {
        return contracts.onlineConsultation().draftStatuses().get("submitted");
    }

    public record DraftView(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("conversation_id") Long conversationId,
            @JsonProperty("health_profile_id") Long healthProfileId,
            DraftSummaryView summary,
            @JsonProperty("current_consultation_id") Long currentConsultationId,
            @JsonProperty("created_at") String createdAt) {}

    /** 草稿摘要视图：无快照时整体为 null（summary_updated_at 是快照存在性的唯一判据）。 */
    public record DraftSummaryView(
            @JsonProperty("chief_complaint") String chiefComplaint,
            @JsonProperty("present_illness") String presentIllness,
            @JsonProperty("allergy_history") String allergyHistory,
            String disclaimer,
            @JsonProperty("suggested_standard_department_id") Long suggestedStandardDepartmentId,
            @JsonProperty("suggested_standard_department_name") String suggestedStandardDepartmentName,
            @JsonProperty("updated_at") String updatedAt) {}
}

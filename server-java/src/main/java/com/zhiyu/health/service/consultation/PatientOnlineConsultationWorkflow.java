package com.zhiyu.health.service.consultation;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.PreconsultationDraft;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.entity.consultation.OnlineConsultationMessage;
import com.zhiyu.health.mapper.chat.PreconsultationDraftMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.ConsultationDetail;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.ConsultationListItem;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.MessageView;
import com.zhiyu.health.service.consultation.mapping.OnlineConsultationDtoMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 患者侧问诊生命周期：确认建单、本人查询、取消/重提和进行中消息。 */
final class PatientOnlineConsultationWorkflow {
    private static final Logger log = LoggerFactory.getLogger(PatientOnlineConsultationWorkflow.class);

    private final OnlineConsultationMapper mapper;
    private final PreconsultationDraftMapper drafts;
    private final TransactionTemplate transactions;
    private final Contracts contracts;
    private final OnlineConsultationDtoMapper dtoMapper;
    private final OnlineConsultationMessaging messaging;
    private final OnlineConsultationAccess access;

    PatientOnlineConsultationWorkflow(
            OnlineConsultationMapper mapper,
            PreconsultationDraftMapper drafts,
            TransactionTemplate transactions,
            Contracts contracts,
            OnlineConsultationDtoMapper dtoMapper,
            OnlineConsultationMessaging messaging,
            OnlineConsultationAccess access) {
        this.mapper = mapper;
        this.drafts = drafts;
        this.transactions = transactions;
        this.contracts = contracts;
        this.dtoMapper = dtoMapper;
        this.messaging = messaging;
        this.access = access;
    }

    /** 建单与草稿提交同事务；并发重复确认由草稿条件更新和活跃档案唯一约束共同兜底。 */
    ConsultationDetail confirm(long patientId, long draftId) {
        PreconsultationDraft draft = drafts.selectById(draftId);
        if (draft == null || draft.getPatientId() != patientId) {
            throw new ApiException(404, "预问诊草稿不存在");
        }
        if (submitted().equals(draft.getStatus())) {
            OnlineConsultation existing = mapper.selectLatestByDraftId(draftId);
            if (existing == null) {
                throw new IllegalStateException("已提交草稿缺少关联问诊单 draftId=" + draftId);
            }
            return access.patientDetail(existing);
        }
        if (draft.getSummaryUpdatedAt() == null) {
            throw new ApiException(409, access.text("summary_required"));
        }
        if (draft.getSuggestedStandardDepartmentId() == null) {
            throw new ApiException(409, access.text("department_unresolved"));
        }
        try {
            return transactions.execute(status -> {
                OnlineConsultation consultation = fromDraft(patientId, draft);
                mapper.insert(consultation);
                if (drafts.markSubmitted(draft.getId(), submitted(), collecting(), pendingConfirm()) != 1) {
                    // 并发确认输家回滚整个事务，不能留下没有对应提交草稿的孤立问诊单。
                    throw new IllegalStateException("预问诊草稿提交失败 draftId=" + draft.getId());
                }
                return access.patientDetail(mapper.selectDetailedById(consultation.getId()));
            });
        } catch (DataIntegrityViolationException e) {
            OnlineConsultation active = access.activeByProfile(draft.getHealthProfileId());
            if (active != null) {
                return access.patientDetail(active);
            }
            throw e;
        }
    }

    List<ConsultationListItem> listMine(long patientId) {
        access.expireOverdue();
        return mapper.selectByPatient(patientId).stream()
                .map(c -> dtoMapper.toListItem(c, access.statusLabel(c.getStatus())))
                .toList();
    }

    ConsultationDetail detail(long patientId, long id) {
        access.expireOverdue();
        return access.patientDetail(access.requireOwnedByPatient(id, patientId));
    }

    ConsultationDetail cancel(long patientId, long id) {
        access.expireOverdue();
        OnlineConsultation consultation = access.requireOwnedByPatient(id, patientId);
        if (access.cancelled().equals(consultation.getStatus())) {
            return access.patientDetail(consultation);
        }
        if (!access.waiting().equals(consultation.getStatus())
                || mapper.cancel(id, patientId, access.waiting(), access.cancelled()) != 1) {
            throw new ApiException(409, access.text("not_waiting"));
        }
        logDecision("cancel", id);
        return access.patientDetail(mapper.selectDetailedById(id));
    }

    /** 取消/失效后创建新单，保留原摘要快照；并发重提回放同档案已经创建的活跃单。 */
    ConsultationDetail resubmit(long patientId, long id) {
        OnlineConsultation source = access.requireOwnedByPatient(id, patientId);
        if (!access.cancelled().equals(source.getStatus()) && !access.expired().equals(source.getStatus())) {
            throw new ApiException(409, "仅已取消或已失效的问诊可重新提交");
        }
        try {
            ConsultationDetail created = transactions.execute(status -> {
                OnlineConsultation fresh = copyForResubmit(source);
                mapper.insert(fresh);
                return access.patientDetail(mapper.selectDetailedById(fresh.getId()));
            });
            logDecision("resubmit", created.id());
            return created;
        } catch (DataIntegrityViolationException e) {
            OnlineConsultation active = access.activeByProfile(source.getHealthProfileId());
            if (active != null) {
                return access.patientDetail(active);
            }
            throw e;
        }
    }

    List<MessageView> listMessages(long patientId, long id, long afterId) {
        access.requireOwnedByPatient(id, patientId);
        return messaging.list(id, afterId);
    }

    MessageView sendMessage(long patientId, long id, String content) {
        OnlineConsultation consultation = access.requireOwnedByPatient(id, patientId);
        access.requireInProgress(consultation);
        access.requireMethodInitiated(consultation);
        return messaging.append(id, access.senderType("patient"), OnlineConsultationMessage.KIND_TEXT, content);
    }

    MessageView sendImage(long patientId, long id, MultipartFile file) {
        OnlineConsultation consultation = access.requireOwnedByPatient(id, patientId);
        access.requireInProgress(consultation);
        access.requireMethodInitiated(consultation);
        return messaging.sendImage(id, access.senderType("patient"), file);
    }

    private OnlineConsultation fromDraft(long patientId, PreconsultationDraft draft) {
        OnlineConsultation consultation = new OnlineConsultation();
        consultation.setPatientId(patientId);
        consultation.setHealthProfileId(draft.getHealthProfileId());
        consultation.setDraftId(draft.getId());
        consultation.setConversationId(draft.getConversationId());
        consultation.setStandardDepartmentId(draft.getSuggestedStandardDepartmentId());
        consultation.setChiefComplaint(draft.getChiefComplaint());
        consultation.setPresentIllness(draft.getPresentIllness());
        consultation.setAllergyHistory(draft.getAllergyHistory());
        consultation.setSummaryDisclaimer(draft.getSummaryDisclaimer());
        prepareWaiting(consultation);
        return consultation;
    }

    private OnlineConsultation copyForResubmit(OnlineConsultation source) {
        OnlineConsultation fresh = new OnlineConsultation();
        fresh.setPatientId(source.getPatientId());
        fresh.setHealthProfileId(source.getHealthProfileId());
        fresh.setDraftId(source.getDraftId());
        fresh.setConversationId(source.getConversationId());
        fresh.setStandardDepartmentId(source.getStandardDepartmentId());
        fresh.setChiefComplaint(source.getChiefComplaint());
        fresh.setPresentIllness(source.getPresentIllness());
        fresh.setAllergyHistory(source.getAllergyHistory());
        fresh.setSummaryDisclaimer(source.getSummaryDisclaimer());
        prepareWaiting(fresh);
        return fresh;
    }

    private void prepareWaiting(OnlineConsultation consultation) {
        consultation.setStatus(access.waiting());
        consultation.setExpiresAt(
                OffsetDateTime.now().plusSeconds(contracts.onlineConsultation().acceptTimeoutSeconds()));
    }

    private void logDecision(String decision, long consultationId) {
        log.info(
                "online consultation decision={} consultationId={} doctorId=null",
                contracts.onlineConsultation().decisions().get(decision),
                consultationId);
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
}

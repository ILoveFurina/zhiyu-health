package com.zhiyu.health.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.ConsultationRecord;
import com.zhiyu.health.entity.InAppMessage;
import com.zhiyu.health.entity.OnlineConsultation;
import com.zhiyu.health.entity.OnlineConsultationMessage;
import com.zhiyu.health.entity.PreconsultationDraft;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.ConsultationRecordMapper;
import com.zhiyu.health.mapper.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.InAppMessageMapper;
import com.zhiyu.health.mapper.OnlineConsultationMapper;
import com.zhiyu.health.mapper.OnlineConsultationMessageMapper;
import com.zhiyu.health.mapper.PreconsultationDraftMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
import com.zhiyu.health.service.mapping.OnlineConsultationDtoMapper;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 在线问诊模块（票 55，Spec 0003）：摘要确认建单、取消/失效/重新提交、科室待接诊池、
 * 医生原子接受、图文/模拟视频接诊与完成。状态校验、身份派生、超时惰性收敛与并发控制
 * 全部收口在本模块内，controller 不自行拼装状态流转；医患消息只持久化于本模块，
 * 不进 Agent 会话、审计原文与 Agent trace（硬约束 5）。
 */
@Service
@RequiredArgsConstructor
public class OnlineConsultationService {

    private static final Logger log = LoggerFactory.getLogger(OnlineConsultationService.class);

    private final OnlineConsultationMapper consultationMapper;
    private final OnlineConsultationMessageMapper messageMapper;
    private final PreconsultationDraftMapper draftMapper;
    private final StaffUserMapper staffUserMapper;
    private final HealthProfileAllergyMapper allergyMapper;
    private final ConsultationRecordMapper consultationRecordMapper;
    private final TransactionTemplate transactionTemplate;
    private final Contracts contracts;
    private final OnlineConsultationDtoMapper dtoMapper;
    private final MinioStorageService minioStorage;
    private final ObjectMapper objectMapper;
    private final PrescriptionMapper prescriptionMapper;
    private final InAppMessageMapper inAppMessageMapper;
    private final DisclaimerService disclaimers;

    // 演示开关（票 60，与 zhiyu.demo.reset-enabled 同一模式）：随访消息立即可见，
    // 跳过契约 delay_days 延迟；默认关闭，仅演示环境显式置 true。
    @Value("${zhiyu.demo.follow-up-visible-immediately:false}")
    private boolean followUpVisibleImmediately;

    // ------------------------------------------------------------------
    // C 端：确认建单、查询、取消、重新提交、医患消息
    // ------------------------------------------------------------------

    /**
     * 确认摘要并建单（幂等）：已提交草稿只回放关联问诊单；建单与草稿提交同事务。
     * 并发重复确认撞上 uq_online_consultations_active_profile 时改查既有活跃单返回。
     */
    public ConsultationDetail confirm(long patientId, long draftId) {
        PreconsultationDraft draft = draftMapper.selectById(draftId);
        if (draft == null || draft.getPatientId() != patientId) {
            throw new ApiException(404, "预问诊草稿不存在");
        }
        if (submitted().equals(draft.getStatus())) {
            OnlineConsultation existing = consultationMapper.selectLatestByDraftId(draftId);
            if (existing == null) {
                // SUBMITTED 与建单在同一事务提交，此处缺失属数据完整性异常而非业务分支。
                throw new IllegalStateException("已提交草稿缺少关联问诊单 draftId=" + draftId);
            }
            return detailView(existing);
        }
        if (draft.getSummaryUpdatedAt() == null) {
            throw new ApiException(409, text("summary_required"));
        }
        if (draft.getSuggestedStandardDepartmentId() == null) {
            throw new ApiException(409, text("department_unresolved"));
        }
        try {
            return transactionTemplate.execute(status -> {
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
                consultation.setStatus(waiting());
                // 接诊截止时间 = 创建 + 契约 accept_timeout_seconds（端内不散落硬编码）。
                consultation.setExpiresAt(OffsetDateTime.now()
                        .plusSeconds(contracts.onlineConsultation().acceptTimeoutSeconds()));
                consultationMapper.insert(consultation);
                if (draftMapper.markSubmitted(draft.getId(), submitted(), collecting(), pendingConfirm()) != 1) {
                    // 并发确认另一方已提交：整体回滚，由唯一索引兜底不产生重复单。
                    throw new IllegalStateException("预问诊草稿提交失败 draftId=" + draft.getId());
                }
                return detailView(consultationMapper.selectDetailedById(consultation.getId()));
            });
        } catch (DataIntegrityViolationException e) {
            OnlineConsultation active = activeByProfile(draft.getHealthProfileId());
            if (active != null) {
                return detailView(active);
            }
            throw e;
        }
    }

    /** 本人问诊列表（进入模块先惰性收敛过期待接诊单）。 */
    public List<ConsultationListItem> listMine(long patientId) {
        expireOverdue();
        return consultationMapper.selectByPatient(patientId).stream()
                .map(c -> dtoMapper.toListItem(c, statusLabel(c.getStatus())))
                .toList();
    }

    public ConsultationDetail detail(long patientId, long id) {
        expireOverdue();
        return detailView(requireOwnedByPatient(id, patientId));
    }

    /** 患者取消：仅 WAITING_DOCTOR 可取消；已取消重复调用返回当前单（幂等）。 */
    public ConsultationDetail cancel(long patientId, long id) {
        expireOverdue();
        OnlineConsultation consultation = requireOwnedByPatient(id, patientId);
        if (cancelled().equals(consultation.getStatus())) {
            return detailView(consultation);
        }
        if (!waiting().equals(consultation.getStatus())) {
            throw new ApiException(409, text("not_waiting"));
        }
        if (consultationMapper.cancel(id, patientId, waiting(), cancelled()) != 1) {
            throw new ApiException(409, text("not_waiting"));
        }
        logDecision("cancel", id, null);
        return detailView(consultationMapper.selectDetailedById(id));
    }

    /** 取消/失效后复用原摘要重新提交：新单沿用草稿/档案/科室快照，仅刷新状态与截止时间。 */
    public ConsultationDetail resubmit(long patientId, long id) {
        OnlineConsultation source = requireOwnedByPatient(id, patientId);
        if (!cancelled().equals(source.getStatus()) && !expired().equals(source.getStatus())) {
            throw new ApiException(409, "仅已取消或已失效的问诊可重新提交");
        }
        try {
            ConsultationDetail created = transactionTemplate.execute(status -> {
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
                fresh.setStatus(waiting());
                fresh.setExpiresAt(OffsetDateTime.now()
                        .plusSeconds(contracts.onlineConsultation().acceptTimeoutSeconds()));
                consultationMapper.insert(fresh);
                return detailView(consultationMapper.selectDetailedById(fresh.getId()));
            });
            logDecision("resubmit", created.id(), null);
            return created;
        } catch (DataIntegrityViolationException e) {
            // 同一档案已有活跃问诊（如并发重新提交）：幂等回放既有活跃单。
            OnlineConsultation active = activeByProfile(source.getHealthProfileId());
            if (active != null) {
                return detailView(active);
            }
            throw e;
        }
    }

    /** 医患消息增量轮询：仅绑定患者可读；COMPLETED 后只读（任何状态都可拉取历史）。 */
    public List<MessageView> listMessagesForPatient(long patientId, long id, long afterId) {
        requireOwnedByPatient(id, patientId);
        return messageMapper.selectAfterId(id, afterId).stream()
                .map(dtoMapper::toMessageView)
                .toList();
    }

    public MessageView sendMessageForPatient(long patientId, long id, String content) {
        OnlineConsultation consultation = requireOwnedByPatient(id, patientId);
        requireInProgress(consultation);
        requireMethodInitiated(consultation);
        return appendMessage(id, senderType("patient"), OnlineConsultationMessage.KIND_TEXT, content);
    }

    // ------------------------------------------------------------------
    // B 端医生：科室待接诊池、接受、发起方式、医患消息、完成
    // ------------------------------------------------------------------

    /** 科室待接诊池：平台范围按医生实际科室映射的标准科室过滤，进入先惰性收敛过期单。 */
    public List<DoctorListItem> pool(long staffId) {
        long doctorId = requireDoctor(staffId);
        long departmentId = requireStandardDepartment(doctorId);
        expireOverdue();
        return consultationMapper.selectPool(departmentId, waiting()).stream()
                .map(this::toDoctorListItem)
                .toList();
    }

    /** 医生本人接诊记录：可选状态过滤仅支持进行中/已完成。 */
    public List<DoctorListItem> mine(long staffId, String status) {
        long doctorId = requireDoctor(staffId);
        String filter = null;
        if (status != null && !status.isBlank()) {
            if (!inProgress().equals(status) && !completed().equals(status)) {
                throw new ApiException(400, "status 仅支持 IN_PROGRESS/COMPLETED");
            }
            filter = status;
        }
        // 列表入口统一先惰性收敛过期 WAITING（绑定单正常不在此态，收敛保持入口语义一致）
        expireOverdue();
        return consultationMapper.selectMine(doctorId, filter).stream()
                .map(this::toDoctorListItem)
                .toList();
    }

    /** 医生详情：绑定医生或同科室待接诊单可见；查看不推进任何状态（不写库）。 */
    public DoctorConsultationDetail detailForDoctor(long staffId, long id) {
        long doctorId = requireDoctor(staffId);
        // 详情入口先惰性收敛：过期待接诊单收敛后不再对医生可见（Spec 0003）
        expireOverdue();
        OnlineConsultation consultation = consultationMapper.selectDetailedById(id);
        if (consultation == null || !visibleToDoctor(consultation, doctorId)) {
            throw new ApiException(404, "问诊单不存在");
        }
        return toDoctorDetail(consultation);
    }

    /**
     * 原子接受：跨科室在任何写入前 404；单条条件更新同时校验预期状态/未绑定/未过期，
     * 并发只有 affected rows = 1 的医生成功，失败者得到明确冲突（Spec 0003）。
     */
    public DoctorConsultationDetail accept(long staffId, long id) {
        long doctorId = requireDoctor(staffId);
        OnlineConsultation consultation = consultationMapper.selectDetailedById(id);
        if (consultation == null || !visibleToDoctor(consultation, doctorId)) {
            throw new ApiException(404, "问诊单不存在");
        }
        return transactionTemplate.execute(status -> {
            expireOverdue();
            if (consultationMapper.accept(id, doctorId, waiting(), inProgress()) != 1) {
                throw new ApiException(409, text("accept_conflict"));
            }
            appendMessage(id, senderType("system"), OnlineConsultationMessage.KIND_TEXT, text("doctor_accepted"));
            logDecision("accept", id, doctorId);
            return toDoctorDetail(consultationMapper.selectDetailedById(id));
        });
    }

    /** 发起接诊方式：首次设定写库并补系统消息；同方式重复调用幂等，换方式明确冲突。 */
    public DoctorConsultationDetail startMethod(long staffId, long id, String method) {
        if (!contracts.onlineConsultation().isKnownConsultMethod(method)) {
            throw new ApiException(400, "接诊方式不合法");
        }
        long doctorId = requireDoctor(staffId);
        OnlineConsultation consultation = requireBoundToDoctor(id, doctorId);
        requireInProgress(consultation);
        if (consultation.getConsultMethod() != null) {
            if (consultation.getConsultMethod().equals(method)) {
                return toDoctorDetail(consultation);
            }
            throw new ApiException(409, text("method_already_set"));
        }
        return transactionTemplate.execute(status -> {
            if (consultationMapper.startMethod(id, doctorId, inProgress(), method) != 1) {
                // 并发双发：另一请求已设定——重读后按幂等/冲突语义出口。
                OnlineConsultation raced = consultationMapper.selectDetailedById(id);
                if (raced.getConsultMethod() != null && raced.getConsultMethod().equals(method)) {
                    return toDoctorDetail(raced);
                }
                throw new ApiException(409, text("method_already_set"));
            }
            appendMessage(id, senderType("system"), OnlineConsultationMessage.KIND_TEXT, systemTextForMethod(method));
            return toDoctorDetail(consultationMapper.selectDetailedById(id));
        });
    }

    public List<MessageView> listMessagesForDoctor(long staffId, long id, long afterId) {
        long doctorId = requireDoctor(staffId);
        requireBoundToDoctor(id, doctorId);
        return messageMapper.selectAfterId(id, afterId).stream()
                .map(dtoMapper::toMessageView)
                .toList();
    }

    public MessageView sendMessageForDoctor(long staffId, long id, String content) {
        long doctorId = requireDoctor(staffId);
        OnlineConsultation consultation = requireBoundToDoctor(id, doctorId);
        requireInProgress(consultation);
        requireMethodInitiated(consultation);
        return appendMessage(id, senderType("doctor"), OnlineConsultationMessage.KIND_TEXT, content);
    }

    /**
     * 完成问诊（幂等）：状态推进与接诊记录写入同一事务——先条件 UPDATE（并发完成只有一个
     * affected rows = 1，输家在 UPDATE 处即 409），再 insert consultation_records；
     * 其 online_consultation_id UNIQUE 由同一事务保证不被撞库。已完成重复调用直接返回当前单。
     * 同事务 eager 写随访关怀站内消息（票 60，见 writeFollowUpMessage）。
     */
    public DoctorConsultationDetail complete(long staffId, long id, String diagnosis, String advice) {
        long doctorId = requireDoctor(staffId);
        OnlineConsultation consultation = requireBoundToDoctor(id, doctorId);
        if (completed().equals(consultation.getStatus())) {
            return toDoctorDetail(consultation);
        }
        requireInProgress(consultation);
        return transactionTemplate.execute(status -> {
            if (consultationMapper.complete(id, doctorId, inProgress(), completed()) != 1) {
                throw new ApiException(409, text("not_in_progress"));
            }
            ConsultationRecord record = new ConsultationRecord();
            record.setOnlineConsultationId(id);
            record.setDoctorId(doctorId);
            record.setDiagnosis(diagnosis.trim());
            record.setAdvice(advice.trim());
            consultationRecordMapper.insert(record);
            writeFollowUpMessage(consultation);
            appendMessage(id, senderType("system"), OnlineConsultationMessage.KIND_TEXT, text("consult_completed"));
            logDecision("complete", id, doctorId);
            return toDoctorDetail(consultationMapper.selectDetailedById(id));
        });
    }

    /**
     * 接诊抽屉按问诊单查处方（票 60）：医生只能查本人绑定单（与 detail 同一归属边界），
     * 管理员可查任意单；无处方返回 null 而非 404——「尚未开方」是正常业务态。
     */
    public ConsultationPrescriptionView prescriptionForConsultation(long staffId, long id) {
        StaffUser staff = staffUserMapper.selectById(staffId);
        if (staff != null && StaffUser.ROLE_ADMIN.equals(staff.getRole())) {
            if (consultationMapper.selectDetailedById(id) == null) {
                throw new ApiException(404, "问诊单不存在");
            }
        } else if (staff != null && StaffUser.ROLE_DOCTOR.equals(staff.getRole()) && staff.getDoctorId() != null) {
            requireBoundToDoctor(id, staff.getDoctorId());
        } else {
            throw new ApiException(403, "仅医生或管理员可操作");
        }
        Prescription prescription = prescriptionMapper.selectByOnlineConsultationId(id);
        if (prescription == null) {
            return null;
        }
        // 状态标签只经 prescription-flow 契约映射，不落私有枚举。
        String label = contracts
                .prescriptionFlow()
                .statusLabels()
                .getOrDefault(prescription.getStatus(), prescription.getStatus());
        return new ConsultationPrescriptionView(
                prescription.getId(), prescription.getStatus(), label, prescription.getReviewReason());
    }

    /**
     * 随访关怀站内消息（票 60）：与问诊完成同事务 eager 写入，type/title/content 只取 contracts；
     * visible_at = 完成时间 + 契约 delay_days 天，患者消息列表由 visible_at <= now() 延迟可见（B2）；
     * 演示开关 follow-up-visible-immediately 置 true 时不设 visible_at，走 DB 默认 now() 立即可见。
     * 撞 UNIQUE(related_online_consultation_id, type)（重试/并发越过 complete 幂等早返回）静默吞掉，不冒 500。
     */
    private void writeFollowUpMessage(OnlineConsultation consultation) {
        Contracts.OnlineConsultation.FollowUp followUp =
                contracts.onlineConsultation().followUp();
        InAppMessage message = new InAppMessage();
        message.setPatientId(consultation.getPatientId());
        message.setType(followUp.messageType());
        message.setTitle(followUp.title());
        message.setContent(followUp.content());
        // server-java 出口兜底：免责声明一律经 DisclaimerService 从契约注入，不信任上游。
        message.setDisclaimer(disclaimers.text());
        message.setRelatedOnlineConsultationId(consultation.getId());
        if (!followUpVisibleImmediately) {
            message.setVisibleAt(OffsetDateTime.now().plusDays(followUp.delayDays()));
        }
        try {
            inAppMessageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            // 见方法注释：UNIQUE 兜底并发/重试，不冒 500。
        }
    }

    // ------------------------------------------------------------------
    // 内部：身份派生、状态守卫、视图装配、契约取值
    // ------------------------------------------------------------------

    /** B 端医生身份派生（与 ReceptionService 同一模式）：角色与绑定关系只信员工账号记录。 */
    private long requireDoctor(long staffId) {
        StaffUser staff = staffUserMapper.selectById(staffId);
        if (staff == null || !StaffUser.ROLE_DOCTOR.equals(staff.getRole()) || staff.getDoctorId() == null) {
            throw new ApiException(403, "仅医生可操作");
        }
        return staff.getDoctorId();
    }

    /** 医生实际科室映射的标准科室是科室池可见性与接诊资格的唯一判据。 */
    private long requireStandardDepartment(long doctorId) {
        Long departmentId = consultationMapper.selectStandardDepartmentIdByDoctor(doctorId);
        if (departmentId == null) {
            throw new ApiException(409, "医生科室未映射标准科室，暂不可接诊在线问诊");
        }
        return departmentId;
    }

    /** 医生可见性：本人绑定单，或同标准科室的待接诊单；其余一律不可见。 */
    private boolean visibleToDoctor(OnlineConsultation consultation, long doctorId) {
        if (consultation.getDoctorId() != null && consultation.getDoctorId() == doctorId) {
            return true;
        }
        if (!waiting().equals(consultation.getStatus())) {
            return false;
        }
        Long departmentId = consultationMapper.selectStandardDepartmentIdByDoctor(doctorId);
        return departmentId != null && departmentId.equals(consultation.getStandardDepartmentId());
    }

    private OnlineConsultation requireOwnedByPatient(long id, long patientId) {
        OnlineConsultation consultation = consultationMapper.selectDetailedByIdAndPatient(id, patientId);
        if (consultation == null) {
            // 归属/不存在一律 404，不区分原因以免泄露存在性（对齐票 27 决策 3）。
            throw new ApiException(404, "问诊单不存在");
        }
        return consultation;
    }

    private OnlineConsultation requireBoundToDoctor(long id, long doctorId) {
        OnlineConsultation consultation = consultationMapper.selectDetailedById(id);
        if (consultation == null || consultation.getDoctorId() == null || consultation.getDoctorId() != doctorId) {
            throw new ApiException(404, "问诊单不存在");
        }
        return consultation;
    }

    private void requireInProgress(OnlineConsultation consultation) {
        if (!inProgress().equals(consultation.getStatus())) {
            throw new ApiException(409, text("not_in_progress"));
        }
    }

    /** 图文/视频都由医生明确发起后才允许双向发消息（Spec 0003：接诊方式只能在接受后发起）。 */
    private void requireMethodInitiated(OnlineConsultation consultation) {
        if (consultation.getConsultMethod() == null) {
            throw new ApiException(409, text("method_required"));
        }
    }

    /** 接诊方式标签：未发起时为 null，取值只经契约 consult_method_labels。 */
    private String methodLabel(OnlineConsultation consultation) {
        return consultation.getConsultMethod() == null
                ? null
                : contracts.onlineConsultation().consultMethodLabels().get(consultation.getConsultMethod());
    }

    /** 惰性失效收敛：列表/详情/池/接受入口先执行（Spec 0003：不引入调度中间件）。 */
    private void expireOverdue() {
        consultationMapper.expireOverdue(waiting(), expired());
    }

    private OnlineConsultation activeByProfile(long healthProfileId) {
        return consultationMapper.selectActiveByProfile(healthProfileId, waiting(), inProgress());
    }

    private MessageView appendMessage(long consultationId, String senderType, String kind, String content) {
        OnlineConsultationMessage message = new OnlineConsultationMessage();
        message.setConsultationId(consultationId);
        message.setSenderType(senderType);
        message.setKind(kind);
        message.setContent(content);
        messageMapper.insert(message);
        return dtoMapper.toMessageView(message);
    }

    /**
     * 患者发送问诊图片（票 58，ADR-0029）：图片是消息本体，MinIO 写入失败即发送失败（抛错不降级），
     * 与医生头像的可选旁路语义不同。content 存 {"object_key","media_type"} JSON，与 messages 表
     * image kind 同构，C/B 端各自经 /c/photos、/api/b/photos 按 key 回看。
     */
    public MessageView sendImageForPatient(long patientId, long id, MultipartFile file) {
        OnlineConsultation consultation = requireOwnedByPatient(id, patientId);
        requireInProgress(consultation);
        requireMethodInitiated(consultation);
        Contracts.ConsultationPhotoLimits limits = contracts.consultationPhotoLimits();
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "请选择图片");
        }
        if (file.getSize() > limits.maxBytes()) {
            throw new ApiException(400, "图片不能超过 " + (limits.maxBytes() / 1024 / 1024) + "MB");
        }
        String type = file.getContentType();
        if (type == null || !limits.allowedTypes().contains(type)) {
            throw new ApiException(400, "图片仅支持 JPEG/PNG 格式");
        }
        String objectKey = minioStorage.storePhoto(file).orElseThrow(() -> new ApiException(503, "图片发送失败，请稍后重试"));
        ObjectNode content =
                objectMapper.createObjectNode().put("object_key", objectKey).put("media_type", type);
        return appendMessage(id, senderType("patient"), OnlineConsultationMessage.KIND_IMAGE, content.toString());
    }

    private ConsultationDetail detailView(OnlineConsultation consultation) {
        DoctorView doctor = null;
        if (consultation.getDoctorId() != null) {
            OnlineConsultationMapper.DoctorIdentityRow identity =
                    consultationMapper.selectDoctorIdentity(consultation.getDoctorId());
            doctor = identity == null ? null : dtoMapper.toDoctorView(identity);
        }
        String status = consultation.getStatus();
        // 终态分支（CANCELLED/EXPIRED）不占五步进度步，只给 terminal_hint 文案。
        String progressStep = contracts.onlineConsultation().isProgressStatus(status) ? status : null;
        return dtoMapper.toDetail(
                consultation,
                dtoMapper.toSummaryView(consultation),
                statusLabel(status),
                progressStep,
                methodLabel(consultation),
                doctor,
                terminalHint(status));
    }

    private String terminalHint(String status) {
        if (expired().equals(status)) {
            return text("expired_hint") + text("resubmit_hint");
        }
        if (cancelled().equals(status)) {
            return text("cancelled_hint") + text("resubmit_hint");
        }
        return null;
    }

    private DoctorListItem toDoctorListItem(OnlineConsultation consultation) {
        return dtoMapper.toDoctorListItem(
                consultation,
                dtoMapper.toSummaryView(consultation),
                statusLabel(consultation.getStatus()),
                methodLabel(consultation),
                dtoMapper.toPatientRef(consultation),
                profileRef(consultation));
    }

    private DoctorConsultationDetail toDoctorDetail(OnlineConsultation consultation) {
        return dtoMapper.toDoctorDetail(
                consultation,
                dtoMapper.toSummaryView(consultation),
                statusLabel(consultation.getStatus()),
                methodLabel(consultation),
                dtoMapper.toPatientRef(consultation),
                profileRef(consultation));
    }

    private ProfileRef profileRef(OnlineConsultation consultation) {
        return dtoMapper.toProfileRef(consultation, allergyMapper.selectAllergens(consultation.getHealthProfileId()));
    }

    private String systemTextForMethod(String method) {
        return contracts.onlineConsultation().consultMethods().get("video").equals(method)
                ? text("video_started")
                : text("text_started");
    }

    /** 领域日志（审计由 AuditFilter 在 HTTP 入口统一执行）：只记决定值与身份 ID，不记任何原文。 */
    private void logDecision(String decisionKey, long consultationId, Long doctorId) {
        log.info(
                "online consultation decision={} consultationId={} doctorId={}",
                contracts.onlineConsultation().decisions().get(decisionKey),
                consultationId,
                doctorId);
    }

    private String waiting() {
        return contracts.onlineConsultation().statuses().get("waiting_doctor");
    }

    private String inProgress() {
        return contracts.onlineConsultation().statuses().get("in_progress");
    }

    private String completed() {
        return contracts.onlineConsultation().statuses().get("completed");
    }

    private String cancelled() {
        return contracts.onlineConsultation().statuses().get("cancelled");
    }

    private String expired() {
        return contracts.onlineConsultation().statuses().get("expired");
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

    private String senderType(String key) {
        return contracts.onlineConsultation().senderTypes().get(key);
    }

    private String statusLabel(String status) {
        return contracts.onlineConsultation().statusLabels().get(status);
    }

    private String text(String key) {
        return contracts.onlineConsultation().texts().get(key);
    }

    // ------------------------------------------------------------------
    // 视图记录（接口形状；状态标签/文案一律来自契约，时间统一 ISO 字符串）
    // ------------------------------------------------------------------

    /** 问诊单上的病情摘要快照（建单时自草稿整体拷贝）。 */
    public record ConsultationSummaryView(
            @JsonProperty("chief_complaint") String chiefComplaint,
            @JsonProperty("present_illness") String presentIllness,
            @JsonProperty("allergy_history") String allergyHistory) {}

    /** C 端可信医生身份：接受后轮询获得，不信任请求体携带的任何医生信息。
     *  photo_url 为 /api/c/photos 代理相对 URL（票 59，映射在 DTO mapper）。 */
    public record DoctorView(
            String name,
            String title,
            @JsonProperty("photo_url") String photoUrl,
            @JsonProperty("hospital_name") String hospitalName,
            @JsonProperty("department_name") String departmentName) {}

    public record ConsultationListItem(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("health_profile_id") Long healthProfileId,
            @JsonProperty("standard_department_name") String standardDepartmentName,
            @JsonProperty("consult_method") String consultMethod,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("expires_at") String expiresAt) {}

    public record ConsultationDetail(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("progress_step") String progressStep,
            @JsonProperty("standard_department_id") Long standardDepartmentId,
            @JsonProperty("standard_department_name") String standardDepartmentName,
            ConsultationSummaryView summary,
            @JsonProperty("summary_disclaimer") String summaryDisclaimer,
            @JsonProperty("consult_method") String consultMethod,
            @JsonProperty("consult_method_label") String consultMethodLabel,
            @JsonProperty("method_started_at") String methodStartedAt,
            DoctorView doctor,
            String diagnosis,
            String advice,
            @JsonProperty("expires_at") String expiresAt,
            @JsonProperty("accepted_at") String acceptedAt,
            @JsonProperty("completed_at") String completedAt,
            @JsonProperty("cancelled_at") String cancelledAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("terminal_hint") String terminalHint) {}

    public record PatientRef(String nickname) {}

    /** 接诊抽屉处方卡片（票 60）：状态标签来自 prescription-flow 契约，驳回原因原样带出。 */
    public record ConsultationPrescriptionView(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("review_reason") String reviewReason) {}

    /** 医生接受前可见的锁定档案信息：判断是否适合接诊的最小集合。 */
    public record ProfileRef(
            @JsonProperty("display_name") String displayName,
            String gender,
            @JsonProperty("birth_date") String birthDate,
            String relationship,
            List<String> allergies) {}

    /** B 端列表项（科室池与本人记录同形；科室池行的方式/接受/完成时间为 null）。 */
    public record DoctorListItem(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("standard_department_id") Long standardDepartmentId,
            @JsonProperty("standard_department_name") String standardDepartmentName,
            ConsultationSummaryView summary,
            @JsonProperty("summary_disclaimer") String summaryDisclaimer,
            PatientRef patient,
            @JsonProperty("health_profile") ProfileRef healthProfile,
            @JsonProperty("consult_method") String consultMethod,
            @JsonProperty("consult_method_label") String consultMethodLabel,
            @JsonProperty("accepted_at") String acceptedAt,
            @JsonProperty("completed_at") String completedAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("expires_at") String expiresAt) {}

    public record DoctorConsultationDetail(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("standard_department_id") Long standardDepartmentId,
            @JsonProperty("standard_department_name") String standardDepartmentName,
            ConsultationSummaryView summary,
            @JsonProperty("summary_disclaimer") String summaryDisclaimer,
            PatientRef patient,
            @JsonProperty("health_profile") ProfileRef healthProfile,
            @JsonProperty("consult_method") String consultMethod,
            @JsonProperty("consult_method_label") String consultMethodLabel,
            @JsonProperty("method_started_at") String methodStartedAt,
            String diagnosis,
            String advice,
            @JsonProperty("accepted_at") String acceptedAt,
            @JsonProperty("completed_at") String completedAt,
            @JsonProperty("cancelled_at") String cancelledAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("expires_at") String expiresAt) {}

    public record MessageView(
            Long id,
            @JsonProperty("sender_type") String senderType,
            String kind,
            String content,
            @JsonProperty("created_at") String createdAt) {}
}

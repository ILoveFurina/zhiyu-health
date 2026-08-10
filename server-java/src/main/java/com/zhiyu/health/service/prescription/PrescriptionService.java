package com.zhiyu.health.service.prescription;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.common.InAppMessage;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.entity.prescription.PrescriptionItem;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.service.common.DisclaimerService;
import com.zhiyu.health.service.consultation.ClinicalContextService;
import com.zhiyu.health.service.prescription.mapping.PrescriptionDtoMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PrescriptionService extends ServiceImpl<PrescriptionMapper, Prescription> {
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper itemMapper;
    private final TransactionTemplate transactionTemplate;
    private final AgentClient agentClient;
    private final ContraindicationService contraindicationService;
    private final DisclaimerService disclaimers;
    private final Contracts contracts;
    private final PrescriptionDtoMapper dtoMapper;
    private final ClinicalContextService clinicalContexts;
    private final InAppMessageMapper inAppMessageMapper;
    private final PharmacyMedicationMapper pharmacyMedicationMapper;

    /** 医生开方目录（票 88）：只含医生当前院区药房已配置且在售的标准药品，keyword 按药名模糊过滤。 */
    public List<MedicationView> listMedications(long staffId, String keyword) {
        ClinicalContextService.DoctorContext doctor = clinicalContexts.requireDoctorContext(staffId);
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return pharmacyMedicationMapper.selectOnSaleCatalogByCampus(doctor.campusId(), normalized).stream()
                .map(dtoMapper::toMedicationView)
                .toList();
    }

    public ContraindicationResult checkSafety(CheckSafetyCommand command) {
        ClinicalContextService.ClinicalContext context =
                clinicalContexts.requirePrescribableFromAppointment(command.staffId(), command.appointmentId());
        // 患者身份只来自已鉴权医生名下的挂号单，绝不接受请求体传入。
        return contraindicationService.check(new ContraindicationService.CheckCommand(
                context.patientId(), context.healthProfileId(), command.medicationIds()));
    }

    public ContraindicationResult checkSafetyFromOnlineConsultation(CheckSafetyOnlineCommand command) {
        ClinicalContextService.ClinicalContext context = clinicalContexts.requirePrescribableFromOnlineConsultation(
                command.staffId(), command.onlineConsultationId());
        return contraindicationService.check(new ContraindicationService.CheckCommand(
                context.patientId(), context.healthProfileId(), command.medicationIds()));
    }

    public PrescriptionView create(CreateCommand command) {
        ClinicalContextService.ClinicalContext context =
                clinicalContexts.requirePrescribableFromAppointment(command.staffId(), command.appointmentId());
        if (prescriptionMapper.selectByAppointmentId(command.appointmentId()) != null) {
            throw new ApiException(409, "该挂号单已开具电子处方");
        }
        return persist(
                context,
                command.items(),
                dtoMapper.toPrescription(command, context.doctorId(), context.sourceCampusId(), status("pending")));
    }

    /** 在线问诊开方（票 56）：同一问诊最多一张处方；患者/档案/医生身份由统一临床上下文派生。 */
    public PrescriptionView createFromOnlineConsultation(CreateOnlineCommand command) {
        ClinicalContextService.ClinicalContext context = clinicalContexts.requirePrescribableFromOnlineConsultation(
                command.staffId(), command.onlineConsultationId());
        if (prescriptionMapper.selectByOnlineConsultationId(command.onlineConsultationId()) != null) {
            throw new ApiException(409, "该问诊已开具电子处方");
        }
        return persist(
                context,
                command.items(),
                dtoMapper.toOnlinePrescription(
                        command, context.doctorId(), context.sourceCampusId(), status("pending")));
    }

    /** 两来源共用落库主路径：entity 按来源写好对应外键列（另一列为 null，schema XOR 兜底）。 */
    private PrescriptionView persist(
            ClinicalContextService.ClinicalContext context, List<CreateItem> items, Prescription prescription) {
        // 票 88：提交侧复验开方目录——药品须处于开方院区药房在售状态（与医生选药列表同一查询口径），
        // 前端目录只是体验层，不作为边界；下架药品的历史处方不受影响（明细只存标准药品外键）。
        List<Long> medicationIds =
                items.stream().map(CreateItem::medicationId).distinct().toList();
        Map<Long, Medication> medicationById = new java.util.HashMap<>();
        pharmacyMedicationMapper
                .selectOnSaleByCampusAndIds(context.sourceCampusId(), medicationIds)
                .forEach(medication -> medicationById.put(medication.getId(), medication));
        if (medicationById.size() != medicationIds.size()) {
            throw new ApiException(400, "药品不在本院区药房在售目录");
        }
        for (CreateItem item : items) {
            if (item.quantity() == null || item.quantity() < 1) {
                throw new ApiException(400, "配药数量必须为正整数");
            }
        }
        // 提交侧强制复跑同一确定性规则：前端禁用按钮只是体验层，不能作为安全边界。
        ContraindicationResult safety = contraindicationService.check(new ContraindicationService.CheckCommand(
                context.patientId(),
                context.healthProfileId(),
                items.stream().map(CreateItem::medicationId).toList()));
        if (safety.blocked()) {
            throw safetyException(safety);
        }
        Long id;
        try {
            id = transactionTemplate.execute(status -> {
                prescriptionMapper.insert(prescription);
                for (CreateItem input : items) {
                    itemMapper.insert(dtoMapper.toPrescriptionItem(input, prescription.getId()));
                }
                return prescription.getId();
            });
        } catch (DataIntegrityViolationException e) {
            // 并发重复提交越过上方预检撞唯一约束（每来源一对一）：明确冲突，不冒 500。
            throw new ApiException(409, prescription.getOnlineConsultationId() != null ? "该问诊已开具电子处方" : "该挂号单已开具电子处方");
        }
        Prescription created = prescriptionMapper.selectDetailedById(id);
        if (created == null) {
            prescription.setId(id);
            created = prescription;
        }
        return toView(created, pairItems(items, medicationById));
    }

    public List<PrescriptionView> listForReview(String status, String keyword) {
        String normalizedStatus = trimToNull(status);
        // 非空 status 须命中契约枚举；空 status 表示全部状态，不再默认归一化为 pending。
        if (normalizedStatus != null) {
            if (!contracts.prescriptionFlow().statuses().containsValue(normalizedStatus)) {
                throw new ApiException(400, "审核状态无效");
            }
        }
        return prescriptionMapper.selectForReview(normalizedStatus, trimToNull(keyword)).stream()
                .map(this::toView)
                .toList();
    }

    public PrescriptionView review(long reviewerId, long id, String decision, String reason) {
        Prescription prescription = prescriptionMapper.selectDetailedById(id);
        if (prescription == null) {
            throw new ApiException(404, "电子处方不存在");
        }
        if (!status("pending").equals(prescription.getStatus())) {
            throw new ApiException(409, "电子处方已审核");
        }
        String target;
        String interpretation = null;
        String disclaimer = null;
        if (decision("approve").equals(decision)) {
            List<PrescriptionItem> items = itemMapper.selectDetailed(id);
            AgentClient.ClinicalResponse generated = agentClient.explainPrescription(
                    items.stream().map(this::toAgentFact).toList());
            target = status("approved");
            interpretation = generated.content();
            // 患者可见出口使用 Java 侧统一契约，模型字段仅作传输兼容。
            disclaimer = disclaimers.text();
        } else if (decision("reject").equals(decision)) {
            if (reason == null || reason.isBlank()) {
                throw new ApiException(400, "驳回时必须填写原因");
            }
            target = status("rejected");
        } else {
            throw new ApiException(400, "审核决定无效");
        }
        // 解读生成是 HTTP 调用，保持在事务外；事务内只做状态推进 + 审核结果站内消息 + 打卡预生成，
        // 任一失败整体回滚，不留"已审核但患者无感知"的中间态（票 60）。
        String trimmedReason = trimToNull(reason);
        String reviewTarget = target;
        String reviewInterpretation = interpretation;
        String reviewDisclaimer = disclaimer;
        return transactionTemplate.execute(tx -> {
            // 条件更新保证并发审核只有一个决定生效，避免先通过后被另一请求覆盖为驳回。
            if (prescriptionMapper.review(
                            id,
                            reviewTarget,
                            trimmedReason,
                            reviewerId,
                            reviewInterpretation,
                            reviewDisclaimer,
                            status("pending"))
                    != 1) {
                throw new ApiException(409, "电子处方已审核");
            }
            Prescription reviewed = prescriptionMapper.selectDetailedById(id);
            writeReviewResultMessage(reviewed, reviewTarget);
            // 票 88（ADR-0035）：不再「审核通过即生成」用药提醒；提醒改由处方药订单
            // 首次到达 DELIVERED/PICKED_UP 时经 DrugOrderService 幂等生成。
            return toView(reviewed);
        });
    }

    /**
     * 审核结果站内消息（票 60）：与审核状态推进同事务，type/title/content 只取 contracts。
     * 撞 UNIQUE(related_prescription_id, type)（并发/重试越过上方条件更新的极端竞态）由
     * ON CONFLICT DO NOTHING 在数据库层幂等吞掉——消息已存在即视为投递成功，事务不受损、不冒 500。
     */
    private void writeReviewResultMessage(Prescription prescription, String target) {
        Contracts.PrescriptionFlow.ReviewMessage copy = contracts
                .prescriptionFlow()
                .messages()
                .get(status("approved").equals(target) ? "approved" : "rejected");
        InAppMessage message = new InAppMessage();
        message.setPatientId(prescription.getPatientId());
        message.setType(contracts.prescriptionFlow().messageTypes().get("prescription_review_result"));
        message.setTitle(copy.title());
        message.setContent(copy.content());
        // server-java 出口兜底：免责声明一律经 DisclaimerService 从契约注入，不信任上游。
        message.setDisclaimer(disclaimers.text());
        message.setRelatedPrescriptionId(prescription.getId());
        inAppMessageMapper.insertIgnoreConflict(message);
    }

    private List<ItemView> pairItems(List<CreateItem> inputs, Map<Long, Medication> medicationById) {
        return inputs.stream()
                .map(input -> dtoMapper.toCreatedItem(input, medicationById.get(input.medicationId())))
                .toList();
    }

    /** 命中禁忌或数据不完整（fail closed）一律 409 拒绝提交；话术只取 contracts/。 */
    private ApiException safetyException(ContraindicationResult safety) {
        Contracts.Contraindication contract = contracts.contraindication();
        String key = contract.decisions().get("blocked").equals(safety.decision())
                ? "blocked_prescription"
                : "review_required_prescription";
        String message = contract.messages().get(key);
        if (!safety.reasons().isEmpty()) {
            // 契约话术以句号收尾，附原因前去掉避免“。：”双标点。
            String base = message.endsWith("。") ? message.substring(0, message.length() - 1) : message;
            message = base + "：" + String.join("；", safety.reasons());
        }
        return new ApiException(409, message);
    }

    private Map<String, String> toAgentFact(PrescriptionItem item) {
        Map<String, String> fact = new LinkedHashMap<>();
        fact.put("name", item.getMedicationName());
        fact.put("specification", item.getSpecification());
        fact.put("dosage", item.getDosage());
        fact.put("frequency", item.getFrequency());
        fact.put("duration", item.getDuration());
        fact.put("notes", item.getNotes() == null ? "" : item.getNotes());
        return fact;
    }

    private PrescriptionView toView(Prescription prescription) {
        return toView(prescription, dtoMapper.toItemViews(itemMapper.selectDetailed(prescription.getId())));
    }

    private PrescriptionView toView(Prescription prescription, List<ItemView> items) {
        String date = prescription.getScheduleDate() == null
                ? null
                : prescription.getScheduleDate().toString();
        String label = contracts
                .prescriptionFlow()
                .statusLabels()
                .getOrDefault(prescription.getStatus(), prescription.getStatus());
        // 来源派生统一走临床上下文模块（数据库不落 source_type 列），取值与标签只经契约。
        String sourceType = clinicalContexts.sourceTypeOf(prescription);
        String sourceLabel = contracts.prescriptionFlow().sourceTypeLabels().get(sourceType);
        return dtoMapper.toPrescriptionView(prescription, label, sourceType, sourceLabel, date, items);
    }

    private String status(String name) {
        return contracts.prescriptionFlow().statuses().get(name);
    }

    private String decision(String name) {
        return contracts.prescriptionFlow().decisions().get(name);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record MedicationView(Long id, String name, String genericName, String specification, String instructions) {}

    public record CreateItem(
            long medicationId, String dosage, String frequency, String duration, Integer quantity, String notes) {}

    public record CreateCommand(long staffId, long appointmentId, String notes, List<CreateItem> items) {}

    public record CheckSafetyCommand(long staffId, long appointmentId, List<Long> medicationIds) {}

    public record CreateOnlineCommand(long staffId, long onlineConsultationId, String notes, List<CreateItem> items) {}

    public record CheckSafetyOnlineCommand(long staffId, long onlineConsultationId, List<Long> medicationIds) {}

    public record ItemView(
            Long medicationId,
            String name,
            String specification,
            String dosage,
            String frequency,
            String duration,
            Integer quantity,
            String notes) {}

    public record PrescriptionView(
            Long id,
            Long appointmentId,
            Long onlineConsultationId,
            String sourceType,
            String sourceTypeLabel,
            String status,
            String notes,
            String interpretation,
            String disclaimer,
            String patientNickname,
            String doctorName,
            String date,
            String diagnosis,
            String advice,
            List<ItemView> items) {}
}

package com.zhiyu.health.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.PrescriptionItem;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.MedicationMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.service.mapping.PrescriptionDtoMapper;
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
    private final StaffUserMapper staffUserMapper;
    private final MedicationMapper medicationMapper;
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper itemMapper;
    private final TransactionTemplate transactionTemplate;
    private final AgentClient agentClient;
    private final ContraindicationService contraindicationService;
    private final DisclaimerService disclaimers;
    private final Contracts contracts;
    private final PrescriptionDtoMapper dtoMapper;
    private final MedCheckinService medCheckinService;
    private final ClinicalContextService clinicalContexts;

    public List<MedicationView> listMedications(long staffId) {
        requireDoctor(staffId);
        return medicationMapper.selectActive().stream()
                .map(dtoMapper::toMedicationView)
                .toList();
    }

    public ContraindicationResult checkSafety(CheckSafetyCommand command) {
        ClinicalContextService.ClinicalContext context =
                clinicalContexts.requirePrescribableFromAppointment(command.staffId(), command.appointmentId());
        // 患者身份只来自已鉴权医生名下的挂号单，绝不接受请求体传入。
        return contraindicationService.check(
                new ContraindicationService.CheckCommand(context.patientId(), command.medicationIds()));
    }

    public ContraindicationResult checkSafetyFromOnlineConsultation(CheckSafetyOnlineCommand command) {
        ClinicalContextService.ClinicalContext context = clinicalContexts.requirePrescribableFromOnlineConsultation(
                command.staffId(), command.onlineConsultationId());
        return contraindicationService.check(
                new ContraindicationService.CheckCommand(context.patientId(), command.medicationIds()));
    }

    public PrescriptionView create(CreateCommand command) {
        ClinicalContextService.ClinicalContext context =
                clinicalContexts.requirePrescribableFromAppointment(command.staffId(), command.appointmentId());
        if (prescriptionMapper.selectByAppointmentId(command.appointmentId()) != null) {
            throw new ApiException(409, "该挂号单已开具电子处方");
        }
        return persist(
                context, command.items(), dtoMapper.toPrescription(command, context.doctorId(), status("pending")));
    }

    /** 在线问诊开方（票 55）：同一问诊最多一张处方；患者/档案/医生身份由统一临床上下文派生。 */
    public PrescriptionView createFromOnlineConsultation(CreateOnlineCommand command) {
        ClinicalContextService.ClinicalContext context = clinicalContexts.requirePrescribableFromOnlineConsultation(
                command.staffId(), command.onlineConsultationId());
        if (prescriptionMapper.selectByOnlineConsultationId(command.onlineConsultationId()) != null) {
            throw new ApiException(409, "该问诊已开具电子处方");
        }
        return persist(
                context,
                command.items(),
                dtoMapper.toOnlinePrescription(command, context.doctorId(), status("pending")));
    }

    /** 两来源共用落库主路径：entity 按来源写好对应外键列（另一列为 null，schema XOR 兜底）。 */
    private PrescriptionView persist(
            ClinicalContextService.ClinicalContext context, List<CreateItem> items, Prescription prescription) {
        List<Medication> medications = items.stream()
                .map(item -> requireMedication(item.medicationId()))
                .toList();
        // 提交侧强制复跑同一确定性规则：前端禁用按钮只是体验层，不能作为安全边界。
        ContraindicationResult safety = contraindicationService.check(new ContraindicationService.CheckCommand(
                context.patientId(),
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
        return toView(created, pairItems(items, medications));
    }

    public List<PrescriptionView> listForReview(String status) {
        String normalized = status == null || status.isBlank() ? status("pending") : status;
        if (!contracts.prescriptionFlow().statuses().containsValue(normalized)) {
            throw new ApiException(400, "审核状态无效");
        }
        return prescriptionMapper.selectForReview(normalized).stream()
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
        // 条件更新保证并发审核只有一个决定生效，避免先通过后被另一请求覆盖为驳回。
        if (prescriptionMapper.review(
                        id, target, trimToNull(reason), reviewerId, interpretation, disclaimer, status("pending"))
                != 1) {
            throw new ApiException(409, "电子处方已审核");
        }
        // 审核通过才 eager 预生成服药打卡提醒（ADR-0017）；驳回不生成。
        // 生成幂等由 UNIQUE(prescription_item_id, due_date) 兜底，重复审核静默吞掉。
        if (status("approved").equals(target)) {
            medCheckinService.generateForApprovedPrescription(id);
        }
        return toView(prescriptionMapper.selectDetailedById(id));
    }

    private List<ItemView> pairItems(List<CreateItem> inputs, List<Medication> medications) {
        return java.util.stream.IntStream.range(0, inputs.size())
                .mapToObj(i -> {
                    CreateItem input = inputs.get(i);
                    Medication medication = medications.get(i);
                    return dtoMapper.toCreatedItem(input, medication);
                })
                .toList();
    }

    private Medication requireMedication(long id) {
        Medication medication = medicationMapper.selectById(id);
        if (medication == null || !Boolean.TRUE.equals(medication.getIsActive())) {
            throw new ApiException(400, "药品不存在或已停用");
        }
        return medication;
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

    private long requireDoctor(long staffId) {
        StaffUser staff = staffUserMapper.selectById(staffId);
        if (staff == null || !StaffUser.ROLE_DOCTOR.equals(staff.getRole()) || staff.getDoctorId() == null) {
            throw new ApiException(403, "仅医生可操作");
        }
        return staff.getDoctorId();
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

    public record CreateItem(long medicationId, String dosage, String frequency, String duration, String notes) {}

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

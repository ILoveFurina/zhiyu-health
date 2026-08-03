package com.zhiyu.health.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.PrescriptionItem;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.MedicationMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.mapper.ReceptionMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.service.mapping.PrescriptionDtoMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PrescriptionService extends ServiceImpl<PrescriptionMapper, Prescription> {
    private final StaffUserMapper staffUserMapper;
    private final ReceptionMapper receptionMapper;
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

    public List<MedicationView> listMedications(long staffId) {
        requireDoctor(staffId);
        return medicationMapper.selectActive().stream()
                .map(dtoMapper::toMedicationView)
                .toList();
    }

    public ContraindicationResult checkSafety(CheckSafetyCommand command) {
        Appointment appointment = requirePrescribableAppointment(command.staffId(), command.appointmentId());
        // 患者身份只来自已鉴权医生名下的挂号单，绝不接受请求体传入。
        return contraindicationService.check(
                new ContraindicationService.CheckCommand(appointment.getPatientId(), command.medicationIds()));
    }

    public PrescriptionView create(CreateCommand command) {
        Appointment appointment = requirePrescribableAppointment(command.staffId(), command.appointmentId());
        long doctorId = appointment.getDoctorId();
        if (prescriptionMapper.selectByAppointmentId(command.appointmentId()) != null) {
            throw new ApiException(409, "该挂号单已开具电子处方");
        }
        List<Medication> medications = command.items().stream()
                .map(item -> requireMedication(item.medicationId()))
                .toList();
        // 提交侧强制复跑同一确定性规则：前端禁用按钮只是体验层，不能作为安全边界。
        ContraindicationResult safety = contraindicationService.check(new ContraindicationService.CheckCommand(
                appointment.getPatientId(),
                command.items().stream().map(CreateItem::medicationId).toList()));
        if (safety.blocked()) {
            throw safetyException(safety);
        }
        Long id = transactionTemplate.execute(status -> {
            Prescription prescription = dtoMapper.toPrescription(command, doctorId, status("pending"));
            prescriptionMapper.insert(prescription);
            for (CreateItem input : command.items()) {
                itemMapper.insert(dtoMapper.toPrescriptionItem(input, prescription.getId()));
            }
            return prescription.getId();
        });
        Prescription created = prescriptionMapper.selectDetailedById(id);
        if (created == null) {
            created = dtoMapper.toPrescription(command, doctorId, status("pending"));
            created.setId(id);
        }
        return toView(created, pairItems(command.items(), medications));
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

    /** 挂号单归属与可开方校验：医生只能操作自己排班下的挂号单，患者上下文由挂号单派生。 */
    private Appointment requirePrescribableAppointment(long staffId, long appointmentId) {
        long doctorId = requireDoctor(staffId);
        Appointment appointment = receptionMapper.selectAppointment(appointmentId, doctorId);
        if (appointment == null) {
            throw new ApiException(404, "挂号单不存在");
        }
        if (Appointment.STATUS_CANCELLED.equals(appointment.getStatus())) {
            throw new ApiException(409, "已取消挂号不可开方");
        }
        return appointment;
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
        return dtoMapper.toPrescriptionView(prescription, label, date, items);
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
            String status,
            String notes,
            String interpretation,
            String disclaimer,
            String patientNickname,
            String doctorName,
            String date,
            List<ItemView> items) {}
}

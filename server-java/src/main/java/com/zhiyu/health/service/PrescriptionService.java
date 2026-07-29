package com.zhiyu.health.service;

import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PrescriptionService {
    private final StaffUserMapper staffUserMapper;
    private final ReceptionMapper receptionMapper;
    private final MedicationMapper medicationMapper;
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper itemMapper;
    private final TransactionTemplate transactionTemplate;
    private final AgentClient agentClient;
    private final DisclaimerService disclaimers;

    public List<MedicationView> listMedications(long staffId) {
        requireDoctor(staffId);
        return medicationMapper.selectActive().stream()
                .map(this::toMedicationView)
                .toList();
    }

    public PrescriptionView create(CreateCommand command) {
        long doctorId = requireDoctor(command.staffId());
        Appointment appointment = receptionMapper.selectAppointment(command.appointmentId(), doctorId);
        if (appointment == null) {
            throw new ApiException(404, "挂号单不存在");
        }
        if (Appointment.STATUS_CANCELLED.equals(appointment.getStatus())) {
            throw new ApiException(409, "已取消挂号不可开方");
        }
        if (prescriptionMapper.selectByAppointmentId(command.appointmentId()) != null) {
            throw new ApiException(409, "该挂号单已开具电子处方");
        }
        List<Medication> medications = command.items().stream()
                .map(item -> requireMedication(item.medicationId()))
                .toList();
        Long id = transactionTemplate.execute(status -> {
            Prescription prescription = new Prescription();
            prescription.setAppointmentId(command.appointmentId());
            prescription.setDoctorId(doctorId);
            prescription.setStatus(Prescription.STATUS_PENDING);
            prescription.setNotes(trimToNull(command.notes()));
            prescriptionMapper.insert(prescription);
            for (CreateItem input : command.items()) {
                PrescriptionItem item = new PrescriptionItem();
                item.setPrescriptionId(prescription.getId());
                item.setMedicationId(input.medicationId());
                item.setDosage(input.dosage().trim());
                item.setFrequency(input.frequency().trim());
                item.setDuration(input.duration().trim());
                item.setNotes(trimToNull(input.notes()));
                itemMapper.insert(item);
            }
            return prescription.getId();
        });
        Prescription created = prescriptionMapper.selectDetailedById(id);
        if (created == null) {
            created = new Prescription();
            created.setId(id);
            created.setAppointmentId(command.appointmentId());
            created.setStatus(Prescription.STATUS_PENDING);
        }
        return toView(created, pairItems(command.items(), medications));
    }

    public List<PrescriptionView> listForReview(String status) {
        String normalized = status == null || status.isBlank() ? Prescription.STATUS_PENDING : status;
        if (!List.of(Prescription.STATUS_PENDING, Prescription.STATUS_APPROVED, Prescription.STATUS_REJECTED)
                .contains(normalized)) {
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
        if (!Prescription.STATUS_PENDING.equals(prescription.getStatus())) {
            throw new ApiException(409, "电子处方已审核");
        }
        String target;
        String interpretation = null;
        String disclaimer = null;
        if ("APPROVE".equals(decision)) {
            List<PrescriptionItem> items = itemMapper.selectDetailed(id);
            AgentClient.ClinicalResponse generated = agentClient.explainPrescription(
                    items.stream().map(this::toAgentFact).toList());
            target = Prescription.STATUS_APPROVED;
            interpretation = generated.content();
            // 患者可见出口使用 Java 侧统一契约，模型字段仅作传输兼容。
            disclaimer = disclaimers.text();
        } else if ("REJECT".equals(decision)) {
            if (reason == null || reason.isBlank()) {
                throw new ApiException(400, "驳回时必须填写原因");
            }
            target = Prescription.STATUS_REJECTED;
        } else {
            throw new ApiException(400, "审核决定无效");
        }
        // 条件更新保证并发审核只有一个决定生效，避免先通过后被另一请求覆盖为驳回。
        if (prescriptionMapper.review(id, target, trimToNull(reason), reviewerId, interpretation, disclaimer) != 1) {
            throw new ApiException(409, "电子处方已审核");
        }
        return toView(prescriptionMapper.selectDetailedById(id));
    }

    private List<ItemView> pairItems(List<CreateItem> inputs, List<Medication> medications) {
        return java.util.stream.IntStream.range(0, inputs.size())
                .mapToObj(i -> {
                    CreateItem input = inputs.get(i);
                    Medication medication = medications.get(i);
                    return new ItemView(
                            medication.getId(),
                            medication.getName(),
                            medication.getSpecification(),
                            input.dosage(),
                            input.frequency(),
                            input.duration(),
                            input.notes());
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

    private MedicationView toMedicationView(Medication medication) {
        return new MedicationView(
                medication.getId(),
                medication.getName(),
                medication.getGenericName(),
                medication.getSpecification(),
                medication.getInstructions());
    }

    private PrescriptionView toView(Prescription prescription) {
        return toView(
                prescription,
                itemMapper.selectDetailed(prescription.getId()).stream()
                        .map(item -> new ItemView(
                                item.getMedicationId(),
                                item.getMedicationName(),
                                item.getSpecification(),
                                item.getDosage(),
                                item.getFrequency(),
                                item.getDuration(),
                                item.getNotes()))
                        .toList());
    }

    private PrescriptionView toView(Prescription prescription, List<ItemView> items) {
        return new PrescriptionView(
                prescription.getId(),
                prescription.getAppointmentId(),
                Prescription.displayStatus(prescription.getStatus()),
                prescription.getNotes(),
                prescription.getInterpretation(),
                prescription.getDisclaimer(),
                prescription.getPatientNickname(),
                prescription.getDoctorName(),
                prescription.getScheduleDate() == null
                        ? null
                        : prescription.getScheduleDate().toString(),
                items);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record MedicationView(Long id, String name, String genericName, String specification, String instructions) {}

    public record CreateItem(long medicationId, String dosage, String frequency, String duration, String notes) {}

    public record CreateCommand(long staffId, long appointmentId, String notes, List<CreateItem> items) {}

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

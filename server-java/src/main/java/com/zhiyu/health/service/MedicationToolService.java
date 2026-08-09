package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.PrescriptionItem;
import com.zhiyu.health.mapper.MedicationMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 购药工具只读服务（票 75）：为 AI 购药提供数据获取与确认卡装配能力，不扣库存、不建订单。
 *
 * 三项能力对应三个 server-py 工具回调：① OTC 药名模糊查询；② 当前患者已审核处方列表；
 * ③ 按 medication_id（OTC）或 prescription_id（处方药）装配购药确认卡数据（实时单价/库存/总价测算）。
 * 只读路径与 {@link DrugOrderService} 下单路径同源取价（medications.price），但绝不经 FOR UPDATE 加锁、
 * 不调用 deductStock--确认卡只是测算，扣库存只能由下单事务完成（ADR-0032）。
 */
@Service
@RequiredArgsConstructor
public class MedicationToolService {
    private final MedicationMapper medicationMapper;
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper prescriptionItemMapper;
    private final Contracts contracts;
    private final ClinicalContextService clinicalContexts;

    /** 按药名模糊查询在售 OTC 药品（is_prescription=FALSE），供用户点名买药时查药。 */
    public List<MedicationView> searchOtc(String name) {
        String keyword = name == null ? "" : name.trim();
        return medicationMapper.searchActiveOtc(keyword).stream()
                .map(this::toView)
                .toList();
    }

    /** 查当前患者已审核（APPROVED）电子处方，含药品明细，供处方药购药选处方。 */
    public List<PrescriptionCardView> listApprovedPrescriptions(long patientId) {
        String approved = contracts.prescriptionFlow().statuses().get("approved");
        List<Prescription> prescriptions = prescriptionMapper.selectForPatientByStatus(patientId, approved);
        return prescriptions.stream().map(this::toCardView).toList();
    }

    /**
     * 装配购药确认卡所需数据（只读不扣库存）。
     *
     * 入参二选一：medication_id + quantity（OTC）或 prescription_id（处方药）。
     * OTC 路径校验药品须 is_prescription=FALSE；处方药路径校验处方 APPROVED 且归属当前患者，
     * 明细数量默认 1（与下单 {@link DrugOrderService} 处方路径默认值同源）。
     */
    public PrepareOrderView prepare(Long medicationId, Integer quantity, Long prescriptionId, Long patientId) {
        if (prescriptionId != null) {
            // 处方药路径需要患者归属校验，patient_id 缺失（null/0）属非法请求而非"处方不存在"。
            if (patientId == null || patientId <= 0) {
                throw new ApiException(400, "处方药购药确认需要 patient_id");
            }
            return prepareForPrescription(prescriptionId, patientId);
        }
        return prepareForOtc(medicationId, quantity);
    }

    // OTC 路径：按 medication_id 取实时单价/库存，处方药不得走此路径（ADR-0032 硬约束）。
    private PrepareOrderView prepareForOtc(Long medicationId, Integer quantity) {
        if (medicationId == null || medicationId <= 0) {
            throw new ApiException(400, "购药确认需要 medication_id");
        }
        if (quantity == null || quantity < 1) {
            throw new ApiException(400, "OTC 购药数量必须为正整数");
        }
        Medication medication = medicationMapper.selectById(medicationId);
        if (medication == null || Boolean.FALSE.equals(medication.getIsActive())) {
            throw new ApiException(404, "药品不存在或已下架");
        }
        if (Boolean.TRUE.equals(medication.getIsPrescription())) {
            throw new ApiException(409, "处方药须凭已审核电子处方购买");
        }
        PrepareLineView line = new PrepareLineView(
                medication.getId(),
                medication.getName(),
                medication.getSpecification(),
                quantity,
                medication.getPrice(),
                medication.getPrice().multiply(BigDecimal.valueOf(quantity)),
                medication.getStock(),
                medication.getStock() >= quantity);
        BigDecimal total = line.subtotal();
        return new PrepareOrderView(
                contracts.orderFlow().sources().get("otc"), null, List.of(line), total, total, null, null, null);
    }

    // 处方药路径：处方须 APPROVED 且归属当前患者，明细数量默认 1（同下单处方路径默认值）。
    // 用 selectDetailedForPatient（DETAIL_COLUMNS 带 JOIN）取 doctor_name/schedule_date 等投影，
    // 与 selectForPatient 同一患者归属可见性边界。
    private PrepareOrderView prepareForPrescription(Long prescriptionId, long patientId) {
        Prescription prescription = prescriptionMapper.selectDetailedForPatient(prescriptionId, patientId);
        if (prescription == null) {
            throw new ApiException(404, "电子处方不存在");
        }
        String approved = contracts.prescriptionFlow().statuses().get("approved");
        if (!approved.equals(prescription.getStatus())) {
            throw new ApiException(409, "仅已审核通过的电子处方可购药");
        }
        List<PrescriptionItem> items = prescriptionItemMapper.selectDetailed(prescriptionId);
        if (items.isEmpty()) {
            throw new ApiException(409, "电子处方没有可购买的药品");
        }
        // 处方明细单价实时取自 medications（同下单路径价格快照来源），数量默认 1。
        Set<Long> medicationIds = new HashSet<>();
        items.forEach(item -> medicationIds.add(item.getMedicationId()));
        Map<Long, Medication> medicationById = new HashMap<>();
        for (Medication medication : medicationMapper.selectBatchIds(medicationIds)) {
            medicationById.put(medication.getId(), medication);
        }
        List<PrepareLineView> lines = new ArrayList<>();
        for (PrescriptionItem item : items) {
            Medication medication = medicationById.get(item.getMedicationId());
            if (medication == null || Boolean.FALSE.equals(medication.getIsActive())) {
                throw new ApiException(409, "处方药品 " + item.getMedicationName() + " 已下架");
            }
            int qty = 1;
            BigDecimal unitPrice = medication.getPrice();
            lines.add(new PrepareLineView(
                    medication.getId(),
                    medication.getName(),
                    medication.getSpecification(),
                    qty,
                    unitPrice,
                    unitPrice.multiply(BigDecimal.valueOf(qty)),
                    medication.getStock(),
                    medication.getStock() >= qty));
        }
        BigDecimal total = lines.stream().map(PrepareLineView::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        String sourceType = clinicalContexts.sourceTypeOf(prescription);
        return new PrepareOrderView(
                contracts.orderFlow().sources().get("prescription"),
                prescriptionId,
                lines,
                total,
                total,
                sourceType,
                prescription.getDoctorName(),
                prescription.getScheduleDate() != null
                        ? prescription.getScheduleDate().toString()
                        : null);
    }

    private MedicationView toView(Medication medication) {
        return new MedicationView(
                medication.getId(),
                medication.getName(),
                medication.getGenericName(),
                medication.getSpecification(),
                medication.getPrice(),
                medication.getStock(),
                medication.getIsActive());
    }

    private PrescriptionCardView toCardView(Prescription prescription) {
        List<PrescriptionItem> items = prescriptionItemMapper.selectDetailed(prescription.getId());
        List<PrescriptionItemView> itemViews = items.stream()
                .map(item -> new PrescriptionItemView(
                        item.getMedicationId(),
                        item.getMedicationName(),
                        item.getSpecification(),
                        item.getDosage(),
                        item.getFrequency(),
                        item.getDuration()))
                .toList();
        String sourceType = clinicalContexts.sourceTypeOf(prescription);
        String sourceTypeLabel = contracts.prescriptionFlow().sourceTypeLabels().get(sourceType);
        return new PrescriptionCardView(
                prescription.getId(),
                prescription.getDoctorName(),
                sourceType,
                sourceTypeLabel,
                prescription.getScheduleDate() != null
                        ? prescription.getScheduleDate().toString()
                        : null,
                itemViews);
    }

    /** OTC 查药视图：返回购药所需的药名/规格/单价/库存（票 75）。 */
    public record MedicationView(
            Long medicationId,
            String name,
            String genericName,
            String specification,
            BigDecimal price,
            Integer stock,
            Boolean isActive) {}

    /** 已审核处方视图：开方医生 + 来源 + 药品明细（用法用量）。 */
    public record PrescriptionCardView(
            Long prescriptionId,
            String doctorName,
            String sourceType,
            String sourceTypeLabel,
            String date,
            List<PrescriptionItemView> items) {}

    /** 处方明细视图：药名/规格 + 用法用量（dosage/frequency/duration）。 */
    public record PrescriptionItemView(
            Long medicationId, String name, String specification, String dosage, String frequency, String duration) {}

    /** 购药确认卡明细行：单价/数量/小计/库存可用性。 */
    public record PrepareLineView(
            Long medicationId,
            String name,
            String specification,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal,
            Integer stock,
            Boolean available) {}

    /** 购药确认卡数据：药品明细 + 总价测算 + 处方来源（处方药时非空）。 */
    public record PrepareOrderView(
            String source,
            Long prescriptionId,
            List<PrepareLineView> items,
            BigDecimal totalAmount,
            BigDecimal payableAmount,
            String prescriptionSourceType,
            String doctorName,
            String prescriptionDate) {}
}

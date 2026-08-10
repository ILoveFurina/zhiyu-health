package com.zhiyu.health.service;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.entity.prescription.PrescriptionItem;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.consultation.ClinicalContextService;
import com.zhiyu.health.service.prescription.DrugOrderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 购药工具只读门面（票 77 → 票 88 药房感知化）：为 AI 购药提供数据获取与预览卡装配
 * 能力，不扣库存、不建订单。业务查询统一委托 {@link DrugOrderService} 的只读测算路径，
 * 与 C 端统一购药确认页同源取数；server-py 不直写 PostgreSQL。
 *
 * 四项能力对应 server-py 工具回调：① OTC 药名模糊查询（标准目录，无价格/库存——价格库存
 * 语义在院区药房）；② 当前患者已审核处方列表；③ 处方购药预览（锁定处方来源院区药房）；
 * ④ OTC 明细校验回声（只校验存在且 OTC、数量合法，不荐药）。
 */
@Service
@RequiredArgsConstructor
public class MedicationToolService {
    private final MedicationMapper medicationMapper;
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper prescriptionItemMapper;
    private final Contracts contracts;
    private final ClinicalContextService clinicalContexts;
    private final DrugOrderService drugOrderService;

    /** 按药名模糊查询 OTC 标准目录药品（is_prescription=FALSE），供用户点名买药时查药。 */
    public List<MedicationView> searchOtc(String name) {
        String keyword = name == null ? "" : name.trim();
        return medicationMapper.searchOtc(keyword).stream()
                .map(medication -> new MedicationView(
                        medication.getId(),
                        medication.getName(),
                        medication.getGenericName(),
                        medication.getSpecification()))
                .toList();
    }

    /** 查当前患者已审核（APPROVED）电子处方，含药品明细与配药数量，供处方药购药选处方。 */
    public List<PrescriptionCardView> listApprovedPrescriptions(long patientId) {
        String approved = contracts.prescriptionFlow().statuses().get("approved");
        List<Prescription> prescriptions = prescriptionMapper.selectForPatientByStatus(patientId, approved);
        return prescriptions.stream().map(this::toCardView).toList();
    }

    /** 处方购药预览（票 88）：与 C 端 preview 同形状，含处方固化院区与锁定院区药房。 */
    public DrugOrderService.PrescriptionPreviewView prepare(long patientId, long prescriptionId) {
        return drugOrderService.previewPrescription(patientId, prescriptionId);
    }

    /** OTC 明细校验回声：只校验药品存在且为 OTC、数量合法（不荐药），供预览卡装配。 */
    public List<DrugOrderService.OtcItemEcho> otcPrepare(List<DrugOrderService.QuantityInput> items) {
        return drugOrderService.validateOtcItems(items);
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
                        item.getDuration(),
                        item.getQuantity()))
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

    /** OTC 查药视图：标准目录身份字段（票 88 起价格/库存归属院区药房，不在本视图）。 */
    public record MedicationView(Long medicationId, String name, String genericName, String specification) {}

    /** 已审核处方视图：开方医生 + 来源 + 药品明细（用法用量与配药数量）。 */
    public record PrescriptionCardView(
            Long prescriptionId,
            String doctorName,
            String sourceType,
            String sourceTypeLabel,
            String date,
            List<PrescriptionItemView> items) {}

    /** 处方明细视图：药名/规格 + 用法用量（dosage/frequency/duration）+ 配药数量（票 88）。 */
    public record PrescriptionItemView(
            Long medicationId,
            String name,
            String specification,
            String dosage,
            String frequency,
            String duration,
            Integer quantity) {}
}

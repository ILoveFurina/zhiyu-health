package com.zhiyu.health.controller.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.service.MedicationToolService;
import com.zhiyu.health.service.MedicationToolService.MedicationView;
import com.zhiyu.health.service.MedicationToolService.PrescriptionCardView;
import com.zhiyu.health.service.prescription.DrugOrderService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * server-py 购药工具回调接口（票 77 → 票 88 药房感知化）：只做参数校验与响应装配，
 * 委托 {@link MedicationToolService}；均经 AgentCallbackAuthFilter 鉴权。
 *
 * 四个只读端点：① GET /api/agent/medications 按药名查 OTC 标准目录；② GET
 * /api/agent/prescriptions 查患者已审核处方；③ GET /api/agent/drug-orders/prepare
 * 处方购药预览（与 C 端 preview 同形状，含锁定院区药房）；④ GET
 * /api/agent/drug-orders/otc-prepare OTC 明细校验回声（不荐药、不扣库存）。
 */
@Validated
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class MedicationToolController {

    private final MedicationToolService medicationToolService;

    /** 按药名模糊查询 OTC 标准目录药品，供用户点名买药时查药。 */
    @GetMapping("/medications")
    public MedicationList searchMedications(@RequestParam(value = "name", required = false) String name) {
        return new MedicationList(medicationToolService.searchOtc(name));
    }

    /** 查当前患者已审核（APPROVED）电子处方，含药品明细与配药数量。 */
    @GetMapping("/prescriptions")
    public PrescriptionList listApprovedPrescriptions(@RequestParam("patient_id") @Positive long patientId) {
        return new PrescriptionList(medicationToolService.listApprovedPrescriptions(patientId));
    }

    /** 处方购药预览：patient_id 由 server-py 从可信上下文注入，用于处方归属校验。 */
    @GetMapping("/drug-orders/prepare")
    public DrugOrderService.PrescriptionPreviewView prepareDrugOrder(
            @RequestParam("patient_id") @Positive long patientId,
            @RequestParam("prescription_id") @Positive long prescriptionId) {
        return medicationToolService.prepare(patientId, prescriptionId);
    }

    /**
     * OTC 明细校验回声：items 形如 {@code 1:2,3:1}（medication_id:quantity 逗号分隔）。
     * 只校验药品存在且为 OTC、数量合法，不荐药；候选药房与价格库存由 C 端确认页实时查。
     */
    @GetMapping("/drug-orders/otc-prepare")
    public OtcPrepareView otcPrepare(
            @RequestParam("patient_id") @Positive long patientId, @RequestParam("items") @NotBlank String items) {
        // patient_id 保留在签名内（可信上下文注入约定），本端点无归属数据可校验，不使用。
        return new OtcPrepareView(medicationToolService.otcPrepare(DrugOrderService.parseQuantityItems(items)));
    }

    public record MedicationList(@JsonProperty("medications") List<MedicationView> medications) {}

    public record PrescriptionList(@JsonProperty("prescriptions") List<PrescriptionCardView> prescriptions) {}

    public record OtcPrepareView(@JsonProperty("items") List<DrugOrderService.OtcItemEcho> items) {}
}

package com.zhiyu.health.controller.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.service.MedicationToolService;
import com.zhiyu.health.service.MedicationToolService.MedicationView;
import com.zhiyu.health.service.MedicationToolService.PrepareOrderView;
import com.zhiyu.health.service.MedicationToolService.PrescriptionCardView;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * server-py 购药工具回调接口（票 77）：只做参数校验与响应装配，委托 {@link MedicationToolService}。
 *
 * 三个只读端点为 AI 购药提供数据获取能力，均经 AgentCallbackAuthFilter 鉴权（与 /api/agent/doctors 同一鉴权层）：
 * ① GET /api/agent/medications 按药名模糊查 OTC 药品；② GET /api/agent/prescriptions 查患者已审核处方；
 * ③ GET /api/agent/drug-orders/prepare 装配购药确认卡数据（实时单价/库存/总价测算，不扣库存）。
 */
@Validated
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class MedicationToolController {

    private final MedicationToolService medicationToolService;

    /** 按药名模糊查询在售 OTC 药品（is_prescription=FALSE），供用户点名买药时查药。 */
    @GetMapping("/medications")
    public MedicationList searchMedications(@RequestParam(value = "name", required = false) String name) {
        return new MedicationList(medicationToolService.searchOtc(name));
    }

    /** 查当前患者已审核（APPROVED）电子处方，含药品明细，供处方药购药选处方。 */
    @GetMapping("/prescriptions")
    public PrescriptionList listApprovedPrescriptions(@RequestParam("patient_id") @Positive long patientId) {
        return new PrescriptionList(medicationToolService.listApprovedPrescriptions(patientId));
    }

    /**
     * 装配购药确认卡所需数据（只读不扣库存）。
     * 入参二选一：medication_id + quantity（OTC）或 prescription_id（处方药）。
     */
    @GetMapping("/drug-orders/prepare")
    public PrepareOrderView prepareDrugOrder(
            @RequestParam(value = "patient_id", required = false) Long patientId,
            @RequestParam(value = "medication_id", required = false) Long medicationId,
            @RequestParam(value = "quantity", required = false) Integer quantity,
            @RequestParam(value = "prescription_id", required = false) Long prescriptionId) {
        // patient_id 由 server-py 从可信上下文注入：OTC 路径不使用，处方药路径用于归属校验。
        return medicationToolService.prepare(medicationId, quantity, prescriptionId, patientId);
    }

    public record MedicationList(@JsonProperty("medications") List<MedicationView> medications) {}

    public record PrescriptionList(@JsonProperty("prescriptions") List<PrescriptionCardView> prescriptions) {}
}

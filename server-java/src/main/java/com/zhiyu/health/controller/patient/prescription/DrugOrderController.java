package com.zhiyu.health.controller.patient.prescription;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.controller.patient.prescription.mapping.DrugOrderInputMapper;
import com.zhiyu.health.service.prescription.DrugOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端药品订单（票 88，ADR-0035）：统一购药确认页的实时查询与下单、待支付动作与订单视图。
 * 收货信息只在下单时一次性快照；历史订单返回脱敏手机号/地址（脱敏在 service 装配层）。
 */
@Validated
@RestController
@RequestMapping("/api/c/drug-orders")
@RequiredArgsConstructor
public class DrugOrderController {
    private final DrugOrderService service;
    private final DrugOrderInputMapper inputMapper;

    public record ItemInput(
            @JsonProperty("medication_id") @NotNull @Positive Long medicationId, @NotNull @Min(1) Integer quantity) {}

    /**
     * 下单：处方药传 prescription_id（锁处方来源院区药房、复用处方数量）；
     * OTC 传 pharmacy_id + items（显式选择可整单履约的院区药房）。两组互斥，由 service 裁决。
     * 收货信息为一次性快照的扁平三字段（与小程序确认页提交形状一致），PICKUP 订单忽略并置空、不落库。
     */
    public record CreateInput(
            @JsonProperty("prescription_id") @Positive Long prescriptionId,
            @JsonProperty("pharmacy_id") @Positive Long pharmacyId,
            List<@Valid ItemInput> items,
            @JsonProperty("pickup_method") @NotBlank String pickupMethod,
            @JsonProperty("receiver_name") String receiverName,
            @JsonProperty("receiver_phone") String receiverPhone,
            @JsonProperty("receiver_address") String receiverAddress) {}

    /** 处方药购药预览：处方固化院区与锁定药房 + 实时价格/库存测算（不扣库存）。 */
    @GetMapping("/preview")
    public DrugOrderService.PrescriptionPreviewView preview(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @RequestParam("prescription_id") @Positive long prescriptionId) {
        return service.previewPrescription(patientId, prescriptionId);
    }

    /**
     * OTC 候选：items 形如 {@code 1:2,3:1}（medication_id:quantity 逗号分隔）。
     * 只返回当前服务城市内、在售且能整单满足的院区药房；不预选第一家。
     * 已授权定位带 lng/lat：按真实坐标距离升序并下发 distance_meters；未授权不传，按稳定序且无距离。
     */
    @GetMapping("/otc-candidates")
    public DrugOrderService.OtcCandidatesView otcCandidates(
            @RequestParam("items") @NotBlank String items,
            @RequestParam(value = "lng", required = false) Double lng,
            @RequestParam(value = "lat", required = false) Double lat) {
        return service.otcCandidates(DrugOrderService.parseQuantityItems(items), lng, lat);
    }

    @PostMapping
    public DrugOrderService.OrderView create(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @Valid @RequestBody CreateInput input) {
        return service.create(inputMapper.toCommand(patientId, input));
    }

    @GetMapping
    public List<DrugOrderService.OrderView> list(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return service.listForPatient(patientId);
    }

    @GetMapping("/{id}")
    public DrugOrderService.OrderView detail(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long id) {
        return service.detailForPatient(patientId, id);
    }

    @PostMapping("/{id}/pay")
    public DrugOrderService.OrderView pay(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long id) {
        return service.pay(patientId, id);
    }

    @PostMapping("/{id}/cancel")
    public DrugOrderService.OrderView cancel(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long id) {
        return service.cancel(patientId, id);
    }
}

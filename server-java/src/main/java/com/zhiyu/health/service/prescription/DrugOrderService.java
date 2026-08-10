package com.zhiyu.health.service.prescription;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.entity.pharmacy.PharmacyAvailability;
import com.zhiyu.health.entity.pharmacy.PharmacyMedication;
import com.zhiyu.health.entity.pharmacy.PharmacyProfile;
import com.zhiyu.health.entity.prescription.DrugOrder;
import com.zhiyu.health.entity.prescription.DrugOrderFulfillmentEvent;
import com.zhiyu.health.entity.prescription.DrugOrderItem;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.entity.prescription.PrescriptionItem;
import com.zhiyu.health.mapper.pharmacy.CampusPharmacyMapper;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderFulfillmentEventMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderItemMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderMapper;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.pharmacy.CampusPharmacyService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 药品订单（票 88，ADR-0035）：院区药房整单履约模型。
 * - 下单：处方药锁处方来源院区药房并完全复用处方数量；OTC 由患者显式提交药房与数量。
 *   事务内锁定药房药品行、复验在售与库存后整单原子预扣，任一失败整体回滚（禁止先查后改）。
 * - 待支付：创建即 UNPAID，支付截止 10 分钟（契约）；list/detail/pay/cancel 入口统一惰性
 *   收敛过期订单至 EXPIRED，同事务回补库存并释放处方重试资格，不引入 scheduler。
 * - 支付：仅 UNPAID -> PAID，条件更新与处方一次性核销同事务；支付后无取消/退款/异常分支。
 * - 履约：配送 PAID -> DISPENSING -> SHIPPED -> DELIVERED，自取 PAID -> DISPENSING ->
 *   READY_FOR_PICKUP -> PICKED_UP；每次条件更新、状态时间戳与 append-only 事件同事务。
 *   处方药订单首次到达 DELIVERED/PICKED_UP 时幂等生成用药提醒，OTC 永不。
 * - 隐私：C 端视图收货手机号/地址脱敏（前 3 后 4 / 前 6 字符），B 端履约视图返回明文。
 */
@Service
@RequiredArgsConstructor
public class DrugOrderService extends ServiceImpl<DrugOrderMapper, DrugOrder> {

    private final DrugOrderMapper orderMapper;
    private final DrugOrderItemMapper itemMapper;
    private final DrugOrderFulfillmentEventMapper eventMapper;
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper prescriptionItemMapper;
    private final PharmacyMedicationMapper pharmacyMedicationMapper;
    private final MedicationMapper medicationMapper;
    private final CampusPharmacyService campusPharmacyService;
    private final CampusPharmacyMapper campusPharmacyMapper;
    private final TransactionTemplate transactionTemplate;
    private final Contracts contracts;
    private final MedCheckinService medCheckinService;

    // ---------- C 端购药查询 ----------

    /**
     * 处方药购药预览（只读不扣库存）：返回处方固化院区与锁定药房、按处方数量的实时价格/库存测算。
     * 价格库存以统一购药确认页实时校验为准，预览不作最终承诺。
     */
    public PrescriptionPreviewView previewPrescription(long patientId, long prescriptionId) {
        Prescription prescription = prescriptionMapper.selectDetailedForPatient(prescriptionId, patientId);
        if (prescription == null) {
            throw new ApiException(404, "电子处方不存在");
        }
        String approved = contracts.prescriptionFlow().statuses().get("approved");
        if (!approved.equals(prescription.getStatus())) {
            throw new ApiException(409, "仅已审核通过的电子处方可购药");
        }
        if (prescription.getRedeemedAt() != null) {
            throw new ApiException(409, "处方已核销，不可再次购药");
        }
        if (orderMapper.countActiveByPrescription(prescriptionId, status("cancelled"), status("expired")) > 0) {
            throw new ApiException(409, "该处方已有进行中的购药订单");
        }
        PharmacyProfile pharmacy = campusPharmacyMapper.selectProfileByCampusId(prescription.getSourceCampusId());
        if (pharmacy == null) {
            throw new ApiException(409, "处方来源院区药房不存在");
        }
        List<PrescriptionItem> items = prescriptionItemMapper.selectDetailed(prescriptionId);
        if (items.isEmpty()) {
            throw new ApiException(409, "电子处方没有可购买的药品");
        }
        Map<Long, PharmacyMedication> onSaleByMedication = pharmacyMedicationMap(pharmacy.getPharmacyId());
        List<PreviewItemView> lines = new ArrayList<>();
        BigDecimal medicationAmount = BigDecimal.ZERO;
        for (PrescriptionItem item : items) {
            PharmacyMedication pm = onSaleByMedication.get(item.getMedicationId());
            int quantity = item.getQuantity();
            boolean available = pm != null
                    && Boolean.TRUE.equals(pm.getIsOnSale())
                    && pm.getStock() != null
                    && pm.getStock() >= quantity;
            BigDecimal unitPrice = pm == null ? null : pm.getPrice();
            if (unitPrice != null) {
                medicationAmount = medicationAmount.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            }
            lines.add(new PreviewItemView(
                    item.getMedicationId(),
                    item.getMedicationName(),
                    item.getSpecification(),
                    quantity,
                    unitPrice,
                    pm == null ? null : pm.getStock(),
                    available));
        }
        return new PrescriptionPreviewView(
                source("prescription"),
                prescriptionId,
                prescription.getDoctorName(),
                prescription.getScheduleDate() == null
                        ? null
                        : prescription.getScheduleDate().toString(),
                pharmacy.getHospitalName(),
                pharmacy.getCampusName(),
                pharmacy.getCampusAddress(),
                pharmacy.getPharmacyId(),
                pharmacy.getDisplayName(),
                pharmacy.getDeliveryFee(),
                pharmacy.getEstimatedDeliveryMinutes(),
                new PreviewPharmacyRef(
                        pharmacy.getPharmacyId(),
                        pharmacy.getDisplayName(),
                        pharmacy.getDeliveryFee(),
                        pharmacy.getEstimatedDeliveryMinutes()),
                lines,
                medicationAmount);
    }

    /**
     * OTC 候选（只读）：只返回当前服务城市内、在售且能完整满足整篮数量的院区药房。
     * 请求无 city 入参：demo 单服务城市口径即全部院区（schema 注释：服务城市由院区动态聚合，
     * 任何查询不得写死城市）。已授权定位（lng/lat 齐全）时按院区真实坐标球面距离升序并下发
     * distance_meters（缺坐标的院区排最后、距离为 null）；无定位保持 SQL 医院/院区稳定序，
     * 不出 distance_meters、不伪造距离、不预选第一家。
     */
    public OtcCandidatesView otcCandidates(List<QuantityInput> inputs, Double lng, Double lat) {
        List<OtcItemEcho> echoes = validateOtcItems(inputs);
        List<Long> medicationIds =
                echoes.stream().map(OtcItemEcho::medicationId).toList();
        Map<Long, Integer> quantityByMedication = new LinkedHashMap<>();
        echoes.forEach(echo -> quantityByMedication.put(echo.medicationId(), echo.quantity()));

        Map<Long, List<PharmacyAvailability>> rowsByPharmacy = new LinkedHashMap<>();
        for (PharmacyAvailability row : pharmacyMedicationMapper.selectOtcAvailability(medicationIds)) {
            rowsByPharmacy
                    .computeIfAbsent(row.getPharmacyId(), ignored -> new ArrayList<>())
                    .add(row);
        }
        boolean located = lng != null && lat != null;
        List<OtcCandidateView> candidates = new ArrayList<>();
        for (List<PharmacyAvailability> rows : rowsByPharmacy.values()) {
            Map<Long, PharmacyAvailability> byMedication = new HashMap<>();
            rows.forEach(row -> byMedication.put(row.getMedicationId(), row));
            // 整单满足：每个药品都有在售行且库存覆盖数量；不满足的药房整体不出现在候选里。
            boolean fulfillable = quantityByMedication.entrySet().stream().allMatch(entry -> {
                PharmacyAvailability row = byMedication.get(entry.getKey());
                return row != null && row.getStock() != null && row.getStock() >= entry.getValue();
            });
            if (!fulfillable) {
                continue;
            }
            BigDecimal medicationAmount = BigDecimal.ZERO;
            for (OtcItemEcho echo : echoes) {
                PharmacyAvailability row = byMedication.get(echo.medicationId());
                medicationAmount = medicationAmount.add(row.getPrice().multiply(BigDecimal.valueOf(echo.quantity())));
            }
            PharmacyAvailability first = rows.get(0);
            candidates.add(new OtcCandidateView(
                    first.getPharmacyId(),
                    first.getPharmacyDisplayName(),
                    first.getHospitalName(),
                    first.getCampusName(),
                    first.getCampusAddress(),
                    first.getDeliveryFee(),
                    first.getEstimatedDeliveryMinutes(),
                    medicationAmount,
                    located ? distanceMeters(lng, lat, first.getCampusLongitude(), first.getCampusLatitude()) : null));
        }
        if (located) {
            // 缺坐标院区排最后；同距离保持药房 id 序，结果稳定可重现。
            candidates.sort(Comparator.comparing(
                            OtcCandidateView::distanceMeters, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(OtcCandidateView::pharmacyId));
        }
        return new OtcCandidatesView(echoes, candidates);
    }

    /** 球面距离（米，haversine）：院区坐标缺失返回 null（排最后，不伪造距离）。 */
    private static Double distanceMeters(double lng, double lat, Double campusLng, Double campusLat) {
        if (campusLng == null || campusLat == null) {
            return null;
        }
        double radLat1 = Math.toRadians(lat);
        double radLat2 = Math.toRadians(campusLat);
        double dLat = radLat2 - radLat1;
        double dLng = Math.toRadians(campusLng - lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * OTC 明细校验（agent otc-prepare 与 otc-candidates 共用）：只校验药品存在且为 OTC、
     * 数量为正整数，不荐药；回声带出药名/规格供预览卡装配。
     */
    public List<OtcItemEcho> validateOtcItems(List<QuantityInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new ApiException(400, "OTC 下单必须指定药品与数量");
        }
        List<Long> medicationIds = new ArrayList<>();
        for (QuantityInput input : inputs) {
            if (input.medicationId() == null || input.medicationId() <= 0) {
                throw new ApiException(400, "药品标识无效");
            }
            if (input.quantity() == null || input.quantity() < 1) {
                throw new ApiException(400, "OTC 下单数量必须为正整数");
            }
            if (medicationIds.contains(input.medicationId())) {
                throw new ApiException(400, "同一药品重复下单，请合并数量");
            }
            medicationIds.add(input.medicationId());
        }
        Map<Long, Medication> catalog = medicationCatalog(medicationIds);
        List<OtcItemEcho> echoes = new ArrayList<>();
        for (QuantityInput input : inputs) {
            Medication medication = catalog.get(input.medicationId());
            if (medication == null) {
                throw new ApiException(404, "药品不存在");
            }
            // 处方药不得走 OTC 路径（ADR-0032 硬约束）：须凭已审核处方。
            if (Boolean.TRUE.equals(medication.getIsPrescription())) {
                throw new ApiException(409, "处方药须凭已审核电子处方购买");
            }
            echoes.add(new OtcItemEcho(
                    medication.getId(), medication.getName(), medication.getSpecification(), input.quantity()));
        }
        return echoes;
    }

    // ---------- C 端下单 / 支付 / 取消 ----------

    public OrderView create(CreateCommand command) {
        return transactionTemplate.execute(status -> createInTransaction(command));
    }

    public OrderView pay(long patientId, long orderId) {
        return transactionTemplate.execute(status -> payInTransaction(patientId, orderId));
    }

    public OrderView cancel(long patientId, long orderId) {
        return transactionTemplate.execute(status -> cancelInTransaction(patientId, orderId));
    }

    public List<OrderView> listForPatient(long patientId) {
        return transactionTemplate.execute(status -> {
            expireOverdue(patientId);
            return orderMapper.selectForPatient(patientId).stream()
                    .map(order -> toView(order, itemMapper.selectDetailed(order.getId()), true))
                    .toList();
        });
    }

    public OrderView detailForPatient(long patientId, long orderId) {
        return transactionTemplate.execute(status -> {
            expireOverdue(patientId);
            DrugOrder order = orderMapper.selectById(orderId);
            // 越权与不存在统一 404，不泄露他人订单存在性。
            if (order == null || !order.getPatientId().equals(patientId)) {
                throw new ApiException(404, "药品订单不存在");
            }
            return toView(order, itemMapper.selectDetailed(orderId), true);
        });
    }

    // ---------- B 端列表 / 明细 / 履约 ----------

    public AdminOrderPage listForAdmin(String status, String pickupMethod, int page, int size) {
        return transactionTemplate.execute(
                tx -> {
                    expireOverdue(null);
                    String normalizedStatus = normalizeOrderStatus(status);
                    String normalizedPickup = normalizePickupMethodOrNull(pickupMethod);
                    int safePage = Math.max(page, 1);
                    int safeSize = Math.min(Math.max(size, 1), 100);
                    long total = orderMapper.countForAdmin(normalizedStatus, normalizedPickup);
                    List<OrderView> items = orderMapper
                            .selectForAdmin(normalizedStatus, normalizedPickup, (safePage - 1) * safeSize, safeSize)
                            .stream()
                            .map(order -> toView(order, itemMapper.selectDetailed(order.getId()), false))
                            .toList();
                    return new AdminOrderPage(items, total, safePage, safeSize);
                });
    }

    public OrderView detailForAdmin(long orderId) {
        return transactionTemplate.execute(status -> {
            expireOverdue(null);
            DrugOrder order = orderMapper.selectDetailedForAdmin(orderId);
            if (order == null) {
                throw new ApiException(404, "药品订单不存在");
            }
            return toView(order, itemMapper.selectDetailed(orderId), false);
        });
    }

    /**
     * B 端模拟履约推进（admin/pharmacist 人工推进）：每次条件更新、状态时间戳与 append-only
     * 事件同事务；0 行即非法跳转/并发，409 让操作者刷新。处方药订单首次到达
     * DELIVERED/PICKED_UP 时同事务幂等生成用药提醒（唯一索引兜底，重投不重复）。
     */
    public OrderView fulfill(long staffId, long orderId, String decision) {
        return transactionTemplate.execute(status -> fulfillInTransaction(staffId, orderId, decision));
    }

    /** B 端取消（admin/pharmacist）：与 C 端同一规则——仅 UNPAID，条件更新 + 全量回补同事务。 */
    public OrderView cancelForAdmin(long orderId) {
        return transactionTemplate.execute(status -> {
            DrugOrder order = orderMapper.selectForUpdate(orderId);
            if (order == null) {
                throw new ApiException(404, "药品订单不存在");
            }
            String unpaid = status("unpaid");
            if (!unpaid.equals(order.getStatus())) {
                throw new ApiException(409, "仅待支付药品订单可取消");
            }
            if (orderMapper.cancel(orderId, status("cancelled"), unpaid) == 0) {
                throw new ApiException(409, "药品订单状态已变化，请刷新后重试");
            }
            restoreItems(orderId);
            return toView(orderMapper.selectDetailedForAdmin(orderId), itemMapper.selectDetailed(orderId), false);
        });
    }

    // ---------- 事务内实现 ----------

    private OrderView createInTransaction(CreateCommand command) {
        String pickupMethod = normalizePickupMethod(command.pickupMethod());
        Receiver receiver = resolveReceiver(pickupMethod, command.receiver());

        CampusPharmacy pharmacy;
        Prescription prescription = null;
        List<Line> lines;
        if (command.prescriptionId() != null) {
            // 处方药路径（票 88）：强制使用处方来源院区药房并完全复用处方数量，患者不可改。
            prescription = prescriptionMapper.selectForPatient(command.prescriptionId(), command.patientId());
            String approved = contracts.prescriptionFlow().statuses().get("approved");
            if (prescription == null) {
                throw new ApiException(404, "电子处方不存在");
            }
            if (!approved.equals(prescription.getStatus())) {
                throw new ApiException(409, "仅已审核通过的电子处方可购药");
            }
            if (prescription.getRedeemedAt() != null) {
                throw new ApiException(409, "处方已核销，不可再次购药");
            }
            pharmacy = campusPharmacyService.requireByCampusId(prescription.getSourceCampusId());
            // 处方防重预检；并发穿透由 uq_drug_orders_active_prescription 部分唯一索引兜底。
            if (orderMapper.countActiveByPrescription(command.prescriptionId(), status("cancelled"), status("expired"))
                    > 0) {
                throw new ApiException(409, "该处方已有进行中的购药订单");
            }
            List<PrescriptionItem> items = prescriptionItemMapper.selectDetailed(command.prescriptionId());
            if (items.isEmpty()) {
                throw new ApiException(409, "电子处方没有可购买的药品");
            }
            List<Long> medicationIds =
                    items.stream().map(PrescriptionItem::getMedicationId).toList();
            Map<Long, PharmacyMedication> locked = lockRows(pharmacy.getId(), medicationIds);
            lines = new ArrayList<>();
            for (PrescriptionItem item : items) {
                PharmacyMedication pm = locked.get(item.getMedicationId());
                if (pm == null || !Boolean.TRUE.equals(pm.getIsOnSale())) {
                    throw new ApiException(409, "处方药品 " + item.getMedicationName() + " 已下架");
                }
                lines.add(new Line(pm, item.getQuantity()));
            }
        } else {
            // OTC 路径：无处方、仅非处方药，患者显式提交候选药房与数量。
            if (command.pharmacyId() == null) {
                throw new ApiException(400, "OTC 下单必须指定履约药房");
            }
            List<OtcItemEcho> echoes = validateOtcItems(command.items());
            pharmacy = campusPharmacyService.requireById(command.pharmacyId());
            List<Long> medicationIds =
                    echoes.stream().map(OtcItemEcho::medicationId).toList();
            Map<Long, PharmacyMedication> locked = lockRows(pharmacy.getId(), medicationIds);
            lines = new ArrayList<>();
            for (OtcItemEcho echo : echoes) {
                PharmacyMedication pm = locked.get(echo.medicationId());
                if (pm == null || !Boolean.TRUE.equals(pm.getIsOnSale())) {
                    throw new ApiException(409, "药品 " + echo.name() + " 在该药房已下架");
                }
                lines.add(new Line(pm, echo.quantity()));
            }
        }

        // 库存只能由带 stock >= n 条件的 UPDATE 预扣；任一药品不足即抛错，事务回滚此前扣减。
        for (Line line : lines) {
            if (pharmacyMedicationMapper.deductStock(line.row().getId(), line.quantity()) == 0) {
                throw new ApiException(409, contracts.orderFlow().messages().get("stock_insufficient"));
            }
        }

        PharmacyProfile profile = campusPharmacyMapper.selectProfileById(pharmacy.getId());
        BigDecimal medicationAmount = lines.stream().map(Line::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean delivery = pickupMethod("delivery").equals(pickupMethod);
        BigDecimal deliveryFee = delivery ? pharmacy.getDeliveryFee() : BigDecimal.ZERO;

        DrugOrder order = new DrugOrder();
        order.setPatientId(command.patientId());
        order.setPrescriptionId(prescription == null ? null : prescription.getId());
        order.setPharmacyId(pharmacy.getId());
        order.setPickupMethod(pickupMethod);
        order.setStatus(status("unpaid"));
        order.setMedicationAmount(medicationAmount);
        order.setDeliveryFee(deliveryFee);
        order.setTotalAmount(medicationAmount.add(deliveryFee));
        // 履约快照：药房/医院/院区名与自取地址（即院区地址），后续配置变化不影响历史订单。
        order.setPharmacyName(pharmacy.getDisplayName());
        order.setHospitalName(profile.getHospitalName());
        order.setCampusName(profile.getCampusName());
        order.setCampusAddress(profile.getCampusAddress());
        order.setReceiverName(receiver.name());
        order.setReceiverPhone(receiver.phone());
        order.setReceiverAddress(receiver.address());
        order.setPaymentDeadline(
                OffsetDateTime.now().plusSeconds(contracts.orderFlow().paymentTimeoutSeconds()));
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发同处方下单撞 uq_drug_orders_active_prescription：确定性 409，不冒 500。
            throw new ApiException(409, "该处方已有进行中的购药订单");
        }
        for (Line line : lines) {
            PharmacyMedication pm = line.row();
            DrugOrderItem item = new DrugOrderItem();
            item.setDrugOrderId(order.getId());
            item.setMedicationId(pm.getMedicationId());
            // 成交时锁定的药房药品在售关系快照：下架/换价后历史订单仍可追溯。
            item.setPharmacyMedicationId(pm.getId());
            item.setQuantity(line.quantity());
            item.setUnitPrice(pm.getPrice());
            item.setSubtotal(line.subtotal());
            itemMapper.insert(item);
        }
        // 回读落库行：created_at 等默认值由 DB 生成，实体不回填，视图与时间线须以落库值为准。
        return toView(orderMapper.selectById(order.getId()), itemMapper.selectDetailed(order.getId()), true);
    }

    private OrderView payInTransaction(long patientId, long orderId) {
        DrugOrder order = orderMapper.selectForPatientForUpdate(orderId, patientId);
        if (order == null) {
            throw new ApiException(404, "药品订单不存在");
        }
        String unpaid = status("unpaid");
        // 支付入口同样惰性收口：已过截止的订单先转 EXPIRED（回补库存），再确定性拒绝支付。
        if (unpaid.equals(order.getStatus()) && order.getPaymentDeadline().isBefore(OffsetDateTime.now())) {
            expireLocked(order);
            throw new ApiException(409, "订单已过支付截止，请重新下单");
        }
        if (!unpaid.equals(order.getStatus())) {
            throw new ApiException(409, "仅待支付药品订单可支付");
        }
        if (orderMapper.markPaid(orderId, status("paid"), unpaid) == 0) {
            throw new ApiException(409, "药品订单状态已变化，请刷新后重试");
        }
        // 处方一次性核销同事务：已核销（重复支付/并发票）0 行即 409 回滚，库存预扣一并释放。
        if (order.getPrescriptionId() != null && prescriptionMapper.redeem(order.getPrescriptionId(), orderId) == 0) {
            throw new ApiException(409, "处方已核销，不可重复支付");
        }
        order.setStatus(status("paid"));
        return toView(order, itemMapper.selectDetailed(orderId), true);
    }

    private OrderView cancelInTransaction(long patientId, long orderId) {
        DrugOrder order = orderMapper.selectForPatientForUpdate(orderId, patientId);
        if (order == null) {
            throw new ApiException(404, "药品订单不存在");
        }
        String unpaid = status("unpaid");
        if (!unpaid.equals(order.getStatus())) {
            throw new ApiException(409, "仅待支付药品订单可取消");
        }
        // 先条件更新裁决再回补：订单行已锁，状态更新与回补同事务提交，跨入口不会重复回补。
        if (orderMapper.cancel(orderId, status("cancelled"), unpaid) == 0) {
            throw new ApiException(409, "药品订单状态已变化，请刷新后重试");
        }
        restoreItems(orderId);
        order.setStatus(status("cancelled"));
        return toView(order, itemMapper.selectDetailed(orderId), true);
    }

    private OrderView fulfillInTransaction(long staffId, long orderId, String decision) {
        String action = decision == null ? "" : decision.trim().toUpperCase();
        DrugOrder order = orderMapper.selectForUpdate(orderId);
        if (order == null) {
            throw new ApiException(404, "药品订单不存在");
        }
        String toStatus;
        int affected;
        if (decision("dispense").equals(action)) {
            toStatus = status("dispensing");
            affected = orderMapper.markDispensing(orderId, toStatus, status("paid"));
        } else if (decision("ship").equals(action)) {
            toStatus = status("shipped");
            // 唯一虚构物流单号：订单号唯一即单号唯一，不接入真实物流。
            String trackingNo = String.format("ZY%010d", orderId);
            affected = orderMapper.markShipped(
                    orderId,
                    toStatus,
                    status("dispensing"),
                    pickupMethod("delivery"),
                    contracts.orderFlow().simulatedCarrierName(),
                    trackingNo);
        } else if (decision("deliver").equals(action)) {
            toStatus = status("delivered");
            affected = orderMapper.markDelivered(orderId, toStatus, status("shipped"));
        } else if (decision("ready").equals(action)) {
            toStatus = status("ready_for_pickup");
            affected = orderMapper.markReadyForPickup(orderId, toStatus, status("dispensing"), pickupMethod("pickup"));
        } else if (decision("pickup").equals(action)) {
            toStatus = status("picked_up");
            affected = orderMapper.markPickedUp(orderId, toStatus, status("ready_for_pickup"));
        } else {
            throw new ApiException(400, "履约动作无效");
        }
        if (affected == 0) {
            throw new ApiException(409, "订单当前状态不允许该履约动作，请刷新后重试");
        }
        DrugOrderFulfillmentEvent event = new DrugOrderFulfillmentEvent();
        event.setDrugOrderId(orderId);
        event.setStatus(toStatus);
        event.setStaffId(staffId);
        eventMapper.insert(event);
        // 处方药订单首次到达交付终态时幂等生成用药提醒（同单重投被条件更新拦截，OTC 永不）。
        if ((status("delivered").equals(toStatus) || status("picked_up").equals(toStatus))
                && order.getPrescriptionId() != null) {
            medCheckinService.generateForDeliveredOrder(order);
        }
        DrugOrder refreshed = orderMapper.selectDetailedForAdmin(orderId);
        return toView(refreshed, itemMapper.selectDetailed(orderId), false);
    }

    // ---------- 共享子程序 ----------

    /** 惰性过期收口：捞出已过支付截止的待支付订单（行锁），逐个条件更新 EXPIRED + 同事务回补。 */
    private void expireOverdue(Long patientId) {
        for (DrugOrder order : orderMapper.selectOverdueUnpaidForUpdate(patientId, status("unpaid"))) {
            expireLocked(order);
        }
    }

    private void expireLocked(DrugOrder order) {
        // 先条件更新裁决：0 行说明并发入口已处理（行锁下理论不可达，兜底防重复回补）。
        if (orderMapper.expire(order.getId(), status("expired"), status("unpaid")) == 0) {
            return;
        }
        restoreItems(order.getId());
        order.setStatus(status("expired"));
    }

    private void restoreItems(long orderId) {
        for (DrugOrderItem item : itemMapper.selectDetailed(orderId)) {
            if (pharmacyMedicationMapper.restoreStock(item.getPharmacyMedicationId(), item.getQuantity()) == 0) {
                throw new ApiException(500, "药品订单库存回补失败");
            }
        }
    }

    private Map<Long, PharmacyMedication> lockRows(long pharmacyId, List<Long> medicationIds) {
        Map<Long, PharmacyMedication> byMedication = new HashMap<>();
        for (PharmacyMedication row :
                pharmacyMedicationMapper.selectForUpdateByPharmacyAndMedicationIds(pharmacyId, medicationIds)) {
            byMedication.put(row.getMedicationId(), row);
        }
        return byMedication;
    }

    private Map<Long, PharmacyMedication> pharmacyMedicationMap(long pharmacyId) {
        Map<Long, PharmacyMedication> byMedication = new HashMap<>();
        for (PharmacyMedication row : pharmacyMedicationMapper.selectDetailedByPharmacy(pharmacyId, null)) {
            byMedication.put(row.getMedicationId(), row);
        }
        return byMedication;
    }

    private Map<Long, Medication> medicationCatalog(List<Long> medicationIds) {
        Map<Long, Medication> byId = new HashMap<>();
        for (Medication medication : medicationMapper.selectBatchIds(medicationIds)) {
            byId.put(medication.getId(), medication);
        }
        return byId;
    }

    private String normalizePickupMethod(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        if (!contracts.orderFlow().pickupMethods().containsValue(value)) {
            throw new ApiException(400, "取药方式无效");
        }
        return value;
    }

    private String normalizePickupMethodOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return normalizePickupMethod(raw);
    }

    private String normalizeOrderStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        if (!contracts.orderFlow().statusLabels().containsKey(value)) {
            throw new ApiException(400, "药品订单状态无效");
        }
        return value;
    }

    /** 收货快照校验：DELIVERY 必三项齐全；PICKUP 不落任何收货信息（CHECK 兜底）。 */
    private Receiver resolveReceiver(String pickupMethod, ReceiverInput input) {
        if (pickupMethod("pickup").equals(pickupMethod)) {
            return new Receiver(null, null, null);
        }
        if (input == null
                || input.name() == null
                || input.name().isBlank()
                || input.phone() == null
                || input.phone().isBlank()
                || input.address() == null
                || input.address().isBlank()) {
            throw new ApiException(400, "配送订单必须填写收货人、手机号与详细地址");
        }
        return new Receiver(
                input.name().trim(), input.phone().trim(), input.address().trim());
    }

    // ---------- 视图装配 ----------

    private OrderView toView(DrugOrder order, List<DrugOrderItem> items, boolean maskSensitive) {
        String statusValue = order.getStatus();
        boolean unpaid = status("unpaid").equals(statusValue);
        Long remainingPaySeconds = unpaid
                ? Math.max(0L, ChronoUnit.SECONDS.between(OffsetDateTime.now(), order.getPaymentDeadline()))
                : null;
        return new OrderView(
                order.getId(),
                order.getPatientId(),
                order.getPatientNickname(),
                order.getPrescriptionId(),
                order.getPrescriptionId() != null ? source("prescription") : source("otc"),
                statusValue,
                contracts.orderFlow().statusLabels().get(statusValue),
                new PharmacyRef(order.getPharmacyId(), order.getPharmacyName()),
                order.getPharmacyName(),
                order.getHospitalName(),
                order.getCampusName(),
                order.getCampusAddress(),
                // 自取地址快照即下单时固化的院区地址；配送单同为药房位置事实，两端同列读取。
                order.getCampusAddress(),
                order.getPickupMethod(),
                contracts.orderFlow().pickupMethodLabels().get(order.getPickupMethod()),
                order.getMedicationAmount(),
                order.getDeliveryFee(),
                order.getTotalAmount(),
                order.getPaymentDeadline() == null
                        ? null
                        : order.getPaymentDeadline().toString(),
                remainingPaySeconds,
                order.getPaidAt() == null ? null : order.getPaidAt().toString(),
                order.getReceiverName(),
                maskSensitive ? maskPhone(order.getReceiverPhone()) : order.getReceiverPhone(),
                maskSensitive ? maskAddress(order.getReceiverAddress()) : order.getReceiverAddress(),
                order.getCarrierName(),
                order.getTrackingNo(),
                unpaid,
                unpaid,
                items.stream().map(this::toItemView).toList(),
                toTimeline(order),
                order.getCreatedAt() == null ? null : order.getCreatedAt().toString());
    }

    private ItemView toItemView(DrugOrderItem item) {
        return new ItemView(
                item.getMedicationId(),
                item.getMedicationName(),
                item.getSpecification(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getSubtotal());
    }

    /**
     * 履约时间线由订单状态时间戳列合成（不落 C 端事件行：events 表 staff_id 非空，
     * 只记录 B 端履约操作；C 端支付/取消/过期由时间戳列如实投影，两端同构）。
     */
    private List<EventView> toTimeline(DrugOrder order) {
        List<EventView> events = new ArrayList<>();
        addEvent(events, status("unpaid"), order.getCreatedAt());
        addEvent(events, status("paid"), order.getPaidAt());
        addEvent(events, status("dispensing"), order.getDispensingAt());
        addEvent(events, status("shipped"), order.getShippedAt());
        addEvent(events, status("delivered"), order.getDeliveredAt());
        addEvent(events, status("ready_for_pickup"), order.getReadyForPickupAt());
        addEvent(events, status("picked_up"), order.getPickedUpAt());
        addEvent(events, status("cancelled"), order.getCancelledAt());
        addEvent(events, status("expired"), order.getExpiredAt());
        return events;
    }

    private void addEvent(List<EventView> events, String statusValue, OffsetDateTime at) {
        if (at == null) {
            return;
        }
        events.add(
                new EventView(statusValue, contracts.orderFlow().statusLabels().get(statusValue), at.toString()));
    }

    /** 手机号脱敏：前 3 后 4，中间 ****；过短号码全掩。 */
    private String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        if (phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 地址脱敏：前 6 字符 + ****。 */
    private String maskAddress(String address) {
        if (address == null) {
            return null;
        }
        return address.length() <= 6 ? "****" : address.substring(0, 6) + "****";
    }

    /** 解析 {@code id:qty,id:qty} 查询参数（C 端 otc-candidates 与 agent otc-prepare 共用）；任何一段非法整体 400。 */
    public static List<QuantityInput> parseQuantityItems(String items) {
        List<QuantityInput> parsed = new ArrayList<>();
        for (String segment : items.split(",")) {
            String[] pair = segment.trim().split(":");
            if (pair.length != 2) {
                throw new ApiException(400, "items 参数格式应为 medication_id:quantity 逗号分隔");
            }
            try {
                parsed.add(new QuantityInput(Long.parseLong(pair[0].trim()), Integer.parseInt(pair[1].trim())));
            } catch (NumberFormatException e) {
                throw new ApiException(400, "items 参数格式应为 medication_id:quantity 逗号分隔");
            }
        }
        return parsed;
    }

    private String status(String name) {
        return contracts.orderFlow().statuses().get(name);
    }

    private String source(String name) {
        return contracts.orderFlow().sources().get(name);
    }

    private String pickupMethod(String name) {
        return contracts.orderFlow().pickupMethods().get(name);
    }

    private String decision(String name) {
        return contracts.orderFlow().decisions().get(name);
    }

    private record Line(PharmacyMedication row, int quantity) {
        private BigDecimal subtotal() {
            return row.getPrice().multiply(BigDecimal.valueOf(quantity));
        }
    }

    private record Receiver(String name, String phone, String address) {}

    // ---------- 契约记录（snake_case 由 Jackson 全局策略序列化） ----------

    public record QuantityInput(Long medicationId, Integer quantity) {}

    public record ReceiverInput(String name, String phone, String address) {}

    public record CreateCommand(
            long patientId,
            Long prescriptionId,
            Long pharmacyId,
            List<QuantityInput> items,
            String pickupMethod,
            ReceiverInput receiver) {}

    public record ItemView(
            Long medicationId,
            String name,
            String specification,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal) {}

    public record EventView(String status, String statusLabel, String occurredAt) {}

    public record PharmacyRef(Long id, String displayName) {}

    /**
     * 订单视图（C/B 同构）：C 端列表/详情与 B 端列表/明细共用；脱敏由 maskSensitive 控制。
     * pharmacy 嵌套引用（契约形状）与 pharmacy_name 扁平快照并存：小程序/admin 读扁平字段。
     */
    public record OrderView(
            Long id,
            Long patientId,
            String patientName,
            Long prescriptionId,
            String source,
            String status,
            String statusLabel,
            PharmacyRef pharmacy,
            String pharmacyName,
            String hospitalName,
            String campusName,
            String campusAddress,
            String pickupAddress,
            String pickupMethod,
            String pickupMethodLabel,
            BigDecimal medicationAmount,
            BigDecimal deliveryFee,
            BigDecimal totalAmount,
            String paymentDeadline,
            Long remainingPaySeconds,
            String paidAt,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String carrierName,
            String trackingNo,
            boolean payable,
            boolean cancellable,
            List<ItemView> items,
            List<EventView> events,
            String createdAt) {}

    public record AdminOrderPage(List<OrderView> records, long total, int page, int size) {}

    /** 处方预览的锁定药房（契约形状：id/display_name/delivery_fee/estimated_delivery_minutes）。 */
    public record PreviewPharmacyRef(
            Long id, String displayName, BigDecimal deliveryFee, Integer estimatedDeliveryMinutes) {}

    public record PreviewItemView(
            Long medicationId,
            String name,
            String specification,
            Integer quantity,
            BigDecimal unitPrice,
            Integer stock,
            Boolean available) {}

    /**
     * 处方药购药预览：扁平字段供统一购药确认页（pharmacy_id/pharmacy_name/estimated_minutes），
     * 嵌套 pharmacy 供 server-py 预览卡投影取 display_name；两端同源取数。
     */
    public record PrescriptionPreviewView(
            String source,
            Long prescriptionId,
            String doctorName,
            String prescriptionDate,
            String hospitalName,
            String campusName,
            String campusAddress,
            Long pharmacyId,
            String pharmacyName,
            BigDecimal deliveryFee,
            Integer estimatedMinutes,
            PreviewPharmacyRef pharmacy,
            List<PreviewItemView> items,
            BigDecimal medicationAmount) {}

    public record OtcItemEcho(Long medicationId, String name, String specification, Integer quantity) {}

    /** OTC 可整单履约候选药房：无定位时 distance_meters 不序列化（NON_NULL），不伪造距离。 */
    public record OtcCandidateView(
            Long pharmacyId,
            String pharmacyName,
            String hospitalName,
            String campusName,
            String campusAddress,
            BigDecimal deliveryFee,
            Integer estimatedMinutes,
            BigDecimal medicationAmount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double distanceMeters) {}

    public record OtcCandidatesView(List<OtcItemEcho> items, List<OtcCandidateView> pharmacies) {}
}

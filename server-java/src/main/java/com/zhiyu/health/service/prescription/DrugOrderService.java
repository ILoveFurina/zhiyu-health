package com.zhiyu.health.service.prescription;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.prescription.DrugOrder;
import com.zhiyu.health.entity.prescription.DrugOrderItem;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.mapper.prescription.DrugOrderItemMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderMapper;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.prescription.mapping.DrugOrderDtoMapper;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class DrugOrderService extends ServiceImpl<DrugOrderMapper, DrugOrder> {
    private final DrugOrderMapper orderMapper;
    private final DrugOrderItemMapper itemMapper;
    private final MedicationMapper medicationMapper;
    private final PrescriptionMapper prescriptionMapper;
    private final TransactionTemplate transactionTemplate;
    private final Contracts contracts;
    private final DrugOrderDtoMapper dtoMapper;

    public OrderView create(CreateCommand command) {
        return transactionTemplate.execute(status -> createInTransaction(command));
    }

    public List<OrderView> listForPatient(long patientId) {
        return orderMapper.selectForPatient(patientId).stream()
                .map(order -> toView(order, itemMapper.selectDetailed(order.getId())))
                .toList();
    }

    public List<OrderView> listForAdmin(String status) {
        String normalizedStatus = status == null || status.isBlank() ? null : status;
        if (normalizedStatus != null && !contracts.orderFlow().statusLabels().containsKey(normalizedStatus)) {
            throw new ApiException(400, "药品订单状态无效");
        }
        return orderMapper.selectForAdmin(normalizedStatus).stream()
                .map(order -> toView(order, itemMapper.selectDetailed(order.getId())))
                .toList();
    }

    public OrderView getForAdmin(long orderId) {
        DrugOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new ApiException(404, "药品订单不存在");
        }
        return toView(order, itemMapper.selectDetailed(orderId));
    }

    public OrderView cancel(long patientId, long orderId) {
        return transactionTemplate.execute(status -> cancelInTransaction(patientId, orderId));
    }

    public OrderView pay(long patientId, long orderId) {
        return transactionTemplate.execute(status -> payInTransaction(patientId, orderId));
    }

    public OrderView completeForAdmin(long orderId) {
        return transactionTemplate.execute(status -> completeInTransaction(orderId));
    }

    public OrderView cancelForAdmin(long orderId) {
        return transactionTemplate.execute(status -> cancelForAdminInTransaction(orderId));
    }

    private OrderView createInTransaction(CreateCommand command) {
        // 票 76（ADR-0032）：双分支--prescriptionId 非空走处方药（须 APPROVED 且药品属处方明细），
        // prescriptionId 为空走 OTC（药品须 is_prescription=FALSE、数量由用户给出）。两条路径共用库存预扣与订单插入。
        List<Medication> medications;
        List<Line> lines;
        if (command.prescriptionId() != null) {
            Prescription prescription =
                    prescriptionMapper.selectForPatient(command.prescriptionId(), command.patientId());
            String approved = contracts.prescriptionFlow().statuses().get("approved");
            if (prescription == null) {
                throw new ApiException(404, "电子处方不存在");
            }
            if (!approved.equals(prescription.getStatus())) {
                throw new ApiException(409, "仅已审核通过的电子处方可购药");
            }
            // 统一按 medication_id 加行锁，既固定并发锁顺序防死锁，也保证本单采用的价格快照稳定。
            medications = medicationMapper.selectForPrescriptionForUpdate(command.prescriptionId());
            if (medications.isEmpty()) {
                throw new ApiException(409, "电子处方没有可购买的药品");
            }
            lines = linesForPrescription(command.items(), medications);
        } else {
            // OTC 路径：必须有 items 且每个药品须 is_prescription=FALSE；行锁按 medication_id 加载。
            if (command.items() == null || command.items().isEmpty()) {
                throw new ApiException(400, "OTC 下单必须指定药品与数量");
            }
            List<Long> medicationIds = command.items().stream()
                    .map(QuantityInput::medicationId)
                    .distinct()
                    .toList();
            medications = medicationMapper.selectByIdsForUpdate(medicationIds);
            if (medications.size() != medicationIds.size()) {
                throw new ApiException(404, "部分药品不存在");
            }
            lines = linesForOtc(command.items(), medications);
        }

        // 库存只能由带 stock >= n 条件的 UPDATE 预扣；任一药品不足即抛错，PG 事务回滚此前扣减。
        for (Line line : lines) {
            if (medicationMapper.deductStock(line.medication().getId(), line.quantity()) == 0) {
                throw new ApiException(409, contracts.orderFlow().messages().get("stock_insufficient"));
            }
        }

        BigDecimal total = lines.stream().map(Line::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        String unpaid = contracts.orderFlow().statuses().get("unpaid");
        DrugOrder order = dtoMapper.toOrder(command, unpaid, total);
        orderMapper.insert(order);
        List<DrugOrderItem> items = lines.stream()
                .map(line -> dtoMapper.toItem(order.getId(), line.medication(), line.quantity(), line.subtotal()))
                .toList();
        items.forEach(itemMapper::insert);
        // source 由 toView 从 prescriptionId 是否为空派生（与读取路径同源，避免重复派生逻辑）。
        return toView(order, items);
    }

    // 处方药明细组装：药品须属处方明细，未指定数量的处方明细默认 1（复用原 lines 逻辑）。
    private List<Line> linesForPrescription(List<QuantityInput> inputs, List<Medication> medications) {
        Set<Long> prescriptionMedicationIds = new HashSet<>();
        medications.forEach(medication -> prescriptionMedicationIds.add(medication.getId()));
        Map<Long, ArrayDeque<Integer>> submittedQuantities = new HashMap<>();
        if (inputs != null) {
            for (QuantityInput input : inputs) {
                if (!prescriptionMedicationIds.contains(input.medicationId())) {
                    throw new ApiException(400, "下单数量包含电子处方以外的药品");
                }
                submittedQuantities
                        .computeIfAbsent(input.medicationId(), ignored -> new ArrayDeque<>())
                        .add(input.quantity());
            }
        }

        List<Line> lines = new ArrayList<>();
        for (Medication medication : medications) {
            ArrayDeque<Integer> quantities = submittedQuantities.get(medication.getId());
            int quantity = quantities == null || quantities.isEmpty() ? 1 : quantities.removeFirst();
            lines.add(new Line(medication, quantity, medication.getPrice().multiply(BigDecimal.valueOf(quantity))));
        }
        if (submittedQuantities.values().stream().anyMatch(quantities -> !quantities.isEmpty())) {
            throw new ApiException(400, "提交的药品数量明细多于电子处方明细");
        }
        return lines;
    }

    // OTC 明细组装：每个药品须 is_prescription=FALSE，数量由用户明确给出（不允许默认）。
    private List<Line> linesForOtc(List<QuantityInput> inputs, List<Medication> medications) {
        Map<Long, Medication> medicationById = new HashMap<>();
        medications.forEach(medication -> medicationById.put(medication.getId(), medication));
        List<Line> lines = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (QuantityInput input : inputs) {
            if (input.quantity() == null || input.quantity() < 1) {
                throw new ApiException(400, "OTC 下单数量必须为正整数");
            }
            Medication medication = medicationById.get(input.medicationId());
            if (medication == null) {
                throw new ApiException(404, "药品不存在");
            }
            // 处方药不得走 OTC 路径（ADR-0032 硬约束）：须凭已审核处方。
            if (Boolean.TRUE.equals(medication.getIsPrescription())) {
                throw new ApiException(409, "处方药须凭已审核电子处方购买");
            }
            if (!seen.add(input.medicationId())) {
                throw new ApiException(400, "同一药品重复下单，请合并数量");
            }
            lines.add(new Line(
                    medication,
                    input.quantity(),
                    medication.getPrice().multiply(BigDecimal.valueOf(input.quantity()))));
        }
        return lines;
    }

    private OrderView cancelInTransaction(long patientId, long orderId) {
        DrugOrder order = orderMapper.selectForPatientForUpdate(orderId, patientId);
        return cancelLocked(order, orderId);
    }

    private OrderView payInTransaction(long patientId, long orderId) {
        DrugOrder order = orderMapper.selectForPatientForUpdate(orderId, patientId);
        if (order == null) {
            throw new ApiException(404, "药品订单不存在");
        }
        String unpaid = contracts.orderFlow().statuses().get("unpaid");
        if (!unpaid.equals(order.getStatus())) {
            throw new ApiException(409, "仅待支付药品订单可支付");
        }
        String paid = contracts.orderFlow().statuses().get("paid");
        if (orderMapper.markPaid(orderId, paid, unpaid) == 0) {
            throw new ApiException(409, "药品订单状态已变化，请刷新后重试");
        }
        order.setStatus(paid);
        return toView(order, itemMapper.selectDetailed(orderId));
    }

    private OrderView completeInTransaction(long orderId) {
        DrugOrder order = orderMapper.selectForUpdate(orderId);
        if (order == null) {
            throw new ApiException(404, "药品订单不存在");
        }
        String paid = contracts.orderFlow().statuses().get("paid");
        if (!paid.equals(order.getStatus())) {
            throw new ApiException(409, "仅已支付药品订单可确认完成");
        }
        String done = contracts.orderFlow().statuses().get("done");
        if (orderMapper.complete(orderId, done, paid) == 0) {
            throw new ApiException(409, "药品订单状态已变化，请刷新后重试");
        }
        order.setStatus(done);
        return toView(order, itemMapper.selectDetailed(orderId));
    }

    private OrderView cancelForAdminInTransaction(long orderId) {
        DrugOrder order = orderMapper.selectForUpdate(orderId);
        return cancelLocked(order, orderId);
    }

    private OrderView cancelLocked(DrugOrder order, long orderId) {
        if (order == null) {
            throw new ApiException(404, "药品订单不存在");
        }
        String unpaid = contracts.orderFlow().statuses().get("unpaid");
        if (!unpaid.equals(order.getStatus())) {
            throw new ApiException(409, "仅待支付药品订单可取消");
        }
        List<DrugOrderItem> items = itemMapper.selectDetailed(orderId);
        // C/B 两端都先锁订单行；库存回补和状态更新同事务提交，避免跨入口重复取消或多补库存。
        for (DrugOrderItem item : items) {
            if (medicationMapper.restoreStock(item.getMedicationId(), item.getQuantity()) == 0) {
                throw new ApiException(500, "药品订单库存回补失败");
            }
        }
        String cancelled = contracts.orderFlow().statuses().get("cancelled");
        if (orderMapper.cancel(orderId, cancelled, unpaid) == 0) {
            throw new ApiException(409, "药品订单状态已变化，请刷新后重试");
        }
        order.setStatus(cancelled);
        return toView(order, items);
    }

    private OrderView toView(DrugOrder order, List<DrugOrderItem> items) {
        boolean unpaid = contracts.orderFlow().statuses().get("unpaid").equals(order.getStatus());
        // source 从 prescriptionId 是否为空派生：非空=处方药订单，空=OTC 订单。
        String source = order.getPrescriptionId() != null
                ? contracts.orderFlow().sources().get("prescription")
                : contracts.orderFlow().sources().get("otc");
        return dtoMapper.toView(
                order,
                source,
                contracts.orderFlow().statusLabels().get(order.getStatus()),
                unpaid,
                unpaid,
                dtoMapper.toItemViews(items));
    }

    private record Line(Medication medication, int quantity, BigDecimal subtotal) {}

    public record QuantityInput(Long medicationId, Integer quantity) {}

    public record CreateCommand(long patientId, Long prescriptionId, List<QuantityInput> items) {}

    public record ItemView(
            Long medicationId,
            String name,
            String specification,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal) {}

    public record OrderView(
            Long id,
            Long patientId,
            Long prescriptionId,
            String source,
            String status,
            String statusLabel,
            BigDecimal totalAmount,
            String createdAt,
            boolean cancellable,
            boolean payable,
            List<ItemView> items) {}
}

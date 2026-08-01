package com.zhiyu.health.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.DrugOrder;
import com.zhiyu.health.entity.DrugOrderItem;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.mapper.DrugOrderItemMapper;
import com.zhiyu.health.mapper.DrugOrderMapper;
import com.zhiyu.health.mapper.MedicationMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.service.mapping.DrugOrderDtoMapper;
import java.math.BigDecimal;
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

    public OrderView cancel(long patientId, long orderId) {
        return transactionTemplate.execute(status -> cancelInTransaction(patientId, orderId));
    }

    private OrderView createInTransaction(CreateCommand command) {
        Prescription prescription = prescriptionMapper.selectForPatient(command.prescriptionId(), command.patientId());
        String approved = contracts.prescriptionFlow().statuses().get("approved");
        if (prescription == null) {
            throw new ApiException(404, "电子处方不存在");
        }
        if (!approved.equals(prescription.getStatus())) {
            throw new ApiException(409, "仅已审核通过的电子处方可购药");
        }

        // 统一按 medication_id 加行锁，既固定并发锁顺序防死锁，也保证本单采用的价格快照稳定。
        List<Medication> medications = medicationMapper.selectForPrescriptionForUpdate(command.prescriptionId());
        if (medications.isEmpty()) {
            throw new ApiException(409, "电子处方没有可购买的药品");
        }
        Map<Long, Integer> quantities = quantities(command.items(), medications);
        List<Line> lines = medications.stream()
                .map(medication -> new Line(
                        medication,
                        quantities.getOrDefault(medication.getId(), 1),
                        medication
                                .getPrice()
                                .multiply(BigDecimal.valueOf(quantities.getOrDefault(medication.getId(), 1)))))
                .toList();

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
        return dtoMapper.toView(
                order, contracts.orderFlow().statusLabels().get(unpaid), null, dtoMapper.toItemViews(items));
    }

    private Map<Long, Integer> quantities(List<QuantityInput> inputs, List<Medication> medications) {
        Map<Long, Integer> quantities = new HashMap<>();
        if (inputs != null) {
            for (QuantityInput input : inputs) {
                if (quantities.put(input.medicationId(), input.quantity()) != null) {
                    throw new ApiException(400, "同一药品不能重复提交数量");
                }
            }
        }
        Set<Long> prescriptionMedicationIds = new HashSet<>();
        medications.forEach(medication -> prescriptionMedicationIds.add(medication.getId()));
        if (!prescriptionMedicationIds.containsAll(quantities.keySet())) {
            throw new ApiException(400, "下单数量包含电子处方以外的药品");
        }
        return quantities;
    }

    private OrderView cancelInTransaction(long patientId, long orderId) {
        DrugOrder order = orderMapper.selectForPatientForUpdate(orderId, patientId);
        if (order == null) {
            throw new ApiException(404, "药品订单不存在");
        }
        String unpaid = contracts.orderFlow().statuses().get("unpaid");
        if (!unpaid.equals(order.getStatus())) {
            throw new ApiException(409, "仅待支付药品订单可取消");
        }
        List<DrugOrderItem> items = itemMapper.selectDetailed(orderId);
        // 订单行锁阻止重复取消；库存回补和状态更新必须同事务提交，避免多补或已取消但未回补。
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
        String createdAt =
                order.getCreatedAt() == null ? null : order.getCreatedAt().toString();
        return dtoMapper.toView(
                order,
                contracts.orderFlow().statusLabels().get(order.getStatus()),
                createdAt,
                dtoMapper.toItemViews(items));
    }

    private record Line(Medication medication, int quantity, BigDecimal subtotal) {}

    public record QuantityInput(Long medicationId, Integer quantity) {}

    public record CreateCommand(long patientId, long prescriptionId, List<QuantityInput> items) {}

    public record ItemView(
            Long medicationId,
            String name,
            String specification,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal) {}

    public record OrderView(
            Long id,
            Long prescriptionId,
            String status,
            String statusLabel,
            BigDecimal totalAmount,
            String createdAt,
            List<ItemView> items) {}
}

package com.zhiyu.health.service.prescription.mapping;

import com.zhiyu.health.entity.prescription.DrugOrder;
import com.zhiyu.health.entity.prescription.DrugOrderItem;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.service.prescription.DrugOrderService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface DrugOrderDtoMapper {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "patientId", source = "command.patientId")
    @Mapping(target = "prescriptionId", source = "command.prescriptionId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "totalAmount", source = "totalAmount")
    DrugOrder toOrder(DrugOrderService.CreateCommand command, String status, BigDecimal totalAmount);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "drugOrderId", source = "orderId")
    @Mapping(target = "medicationId", source = "medication.id")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "unitPrice", source = "medication.price")
    @Mapping(target = "subtotal", source = "subtotal")
    @Mapping(target = "medicationName", source = "medication.name")
    @Mapping(target = "specification", source = "medication.specification")
    DrugOrderItem toItem(long orderId, Medication medication, int quantity, BigDecimal subtotal);

    @Mapping(target = "id", source = "order.id")
    @Mapping(target = "patientId", source = "order.patientId")
    @Mapping(target = "prescriptionId", source = "order.prescriptionId")
    @Mapping(target = "status", source = "order.status")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "totalAmount", source = "order.totalAmount")
    @Mapping(target = "createdAt", source = "order.createdAt", qualifiedByName = "offsetDateTimeText")
    @Mapping(target = "cancellable", source = "cancellable")
    @Mapping(target = "payable", source = "payable")
    @Mapping(target = "items", source = "items")
    DrugOrderService.OrderView toView(
            DrugOrder order,
            String statusLabel,
            boolean cancellable,
            boolean payable,
            List<DrugOrderService.ItemView> items);

    @Mapping(target = "name", source = "medicationName")
    DrugOrderService.ItemView toItemView(DrugOrderItem item);

    List<DrugOrderService.ItemView> toItemViews(List<DrugOrderItem> items);

    @Named("offsetDateTimeText")
    default String offsetDateTimeText(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}

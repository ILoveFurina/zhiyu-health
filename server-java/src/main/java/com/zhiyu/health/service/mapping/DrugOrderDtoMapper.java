package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.DrugOrder;
import com.zhiyu.health.entity.DrugOrderItem;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.service.DrugOrderService;
import java.math.BigDecimal;
import java.util.List;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

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
    @Mapping(target = "prescriptionId", source = "order.prescriptionId")
    @Mapping(target = "status", source = "order.status")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "totalAmount", source = "order.totalAmount")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "items", source = "items")
    DrugOrderService.OrderView toView(
            DrugOrder order, String statusLabel, String createdAt, List<DrugOrderService.ItemView> items);

    @Mapping(target = "name", source = "medicationName")
    DrugOrderService.ItemView toItemView(DrugOrderItem item);

    List<DrugOrderService.ItemView> toItemViews(List<DrugOrderItem> items);
}

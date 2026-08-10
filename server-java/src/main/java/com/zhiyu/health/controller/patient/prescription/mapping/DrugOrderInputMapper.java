package com.zhiyu.health.controller.patient.prescription.mapping;

import com.zhiyu.health.controller.patient.prescription.DrugOrderController;
import com.zhiyu.health.service.prescription.DrugOrderService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DrugOrderInputMapper {
    @Mapping(target = "patientId", source = "patientId")
    @Mapping(target = "prescriptionId", source = "input.prescriptionId")
    @Mapping(target = "pharmacyId", source = "input.pharmacyId")
    @Mapping(target = "items", source = "input.items")
    @Mapping(target = "pickupMethod", source = "input.pickupMethod")
    @Mapping(target = "receiver", expression = "java(toReceiverInput(input))")
    DrugOrderService.CreateCommand toCommand(long patientId, DrugOrderController.CreateInput input);

    DrugOrderService.QuantityInput toQuantityInput(DrugOrderController.ItemInput input);

    /** 收货信息三字段全空则视为未提交（PICKUP 场景），交由 service 按取药方式校验。 */
    default DrugOrderService.ReceiverInput toReceiverInput(DrugOrderController.CreateInput input) {
        if (input.receiverName() == null && input.receiverPhone() == null && input.receiverAddress() == null) {
            return null;
        }
        return new DrugOrderService.ReceiverInput(input.receiverName(), input.receiverPhone(), input.receiverAddress());
    }
}

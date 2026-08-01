package com.zhiyu.health.controller.c.mapping;

import com.zhiyu.health.controller.c.DrugOrderController;
import com.zhiyu.health.service.DrugOrderService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DrugOrderInputMapper {
    @Mapping(target = "patientId", source = "patientId")
    @Mapping(target = "prescriptionId", source = "input.prescriptionId")
    @Mapping(target = "items", source = "input.items")
    DrugOrderService.CreateCommand toCommand(long patientId, DrugOrderController.CreateInput input);

    DrugOrderService.QuantityInput toQuantityInput(DrugOrderController.ItemInput input);
}

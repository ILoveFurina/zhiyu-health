package com.zhiyu.health.controller.b.mapping;

import com.zhiyu.health.controller.b.PrescriptionTemplateController;
import com.zhiyu.health.service.PrescriptionTemplateService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PrescriptionTemplateInputMapper {

    PrescriptionTemplateService.ItemInput toItemInput(PrescriptionTemplateController.ItemInput input);

    @Mapping(target = "staffId", source = "staffId")
    @Mapping(target = "name", source = "input.name")
    @Mapping(target = "items", source = "input.items")
    PrescriptionTemplateService.SaveCommand toSaveCommand(
            long staffId, PrescriptionTemplateController.TemplateInput input);
}

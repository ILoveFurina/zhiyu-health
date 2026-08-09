package com.zhiyu.health.service.prescription.mapping;

import com.zhiyu.health.entity.prescription.PrescriptionTemplate;
import com.zhiyu.health.entity.prescription.PrescriptionTemplateItem;
import com.zhiyu.health.service.prescription.PrescriptionTemplateService;
import java.util.List;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface PrescriptionTemplateDtoMapper {

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "name", source = "command.name", qualifiedByName = "trimRequired")
    @Mapping(target = "doctorId", source = "doctorId")
    PrescriptionTemplate toTemplate(PrescriptionTemplateService.SaveCommand command, long doctorId);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "templateId", source = "templateId")
    @Mapping(target = "medicationId", source = "input.medicationId")
    @Mapping(target = "dosage", source = "input.dosage", qualifiedByName = "trimRequired")
    @Mapping(target = "frequency", source = "input.frequency", qualifiedByName = "trimRequired")
    @Mapping(target = "duration", source = "input.duration", qualifiedByName = "trimRequired")
    @Mapping(target = "notes", source = "input.notes", qualifiedByName = "trimToNull")
    PrescriptionTemplateItem toItem(PrescriptionTemplateService.ItemInput input, long templateId);

    // ItemView 字段与实体同名（含 medicationName），全部按名自动映射。
    PrescriptionTemplateService.ItemView toItemView(PrescriptionTemplateItem item);

    List<PrescriptionTemplateService.ItemView> toItemViews(List<PrescriptionTemplateItem> items);

    @Mapping(target = "id", source = "template.id")
    @Mapping(target = "name", source = "template.name")
    @Mapping(target = "doctorId", source = "template.doctorId")
    @Mapping(target = "createdAt", source = "template.createdAt")
    @Mapping(target = "items", source = "items")
    PrescriptionTemplateService.TemplateView toTemplateView(
            PrescriptionTemplate template, List<PrescriptionTemplateService.ItemView> items);

    @Named("trimRequired")
    default String trimRequired(String value) {
        return value.trim();
    }

    @Named("trimToNull")
    default String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.MedCheckinRecord;
import com.zhiyu.health.service.MedCheckinView;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MedCheckinDtoMapper {

    // status 传入的是契约 label（如"已服用"），由 service 经 contracts.medCheckinFlow().statusLabels() 查得；
    // checkedAt/streak 由 service 处理后传入，mapper 只做字段映射（对齐 PrescriptionDtoMapper 模式）。
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "record.id")
    @Mapping(target = "prescriptionId", source = "record.prescriptionId")
    @Mapping(target = "medicationName", source = "record.medicationName")
    @Mapping(target = "dosage", source = "record.dosage")
    @Mapping(target = "frequency", source = "record.frequency")
    @Mapping(target = "dueDate", source = "record.dueDate")
    @Mapping(target = "status", source = "statusLabel")
    @Mapping(target = "checkedAt", source = "checkedAtText")
    @Mapping(target = "disclaimer", source = "record.disclaimer")
    @Mapping(target = "streak", source = "streak")
    MedCheckinView toView(MedCheckinRecord record, String statusLabel, String checkedAtText, Integer streak);
}

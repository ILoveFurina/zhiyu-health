package com.zhiyu.health.service.pharmacy.mapping;

import com.zhiyu.health.entity.pharmacy.PharmacyMedication;
import com.zhiyu.health.service.pharmacy.PharmacyMedicationService;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PharmacyDtoMapper {

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "pharmacyId", source = "pharmacyId")
    @Mapping(target = "medicationId", source = "command.medicationId")
    @Mapping(target = "price", source = "command.price")
    @Mapping(target = "stock", source = "command.stock")
    @Mapping(target = "isOnSale", source = "command.isOnSale")
    PharmacyMedication toEntity(PharmacyMedicationService.AddCommand command, long pharmacyId);

    @Mapping(target = "name", source = "medicationName")
    PharmacyMedicationService.PharmacyMedicationView toView(PharmacyMedication row);
}

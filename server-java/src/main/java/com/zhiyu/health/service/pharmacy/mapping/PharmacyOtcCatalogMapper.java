package com.zhiyu.health.service.pharmacy.mapping;

import com.zhiyu.health.entity.pharmacy.PharmacyOtcCatalogRow;
import com.zhiyu.health.service.pharmacy.PharmacyOtcCatalogService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PharmacyOtcCatalogMapper {

    /** 药品名列为 name（投影列 medication_name 改名，与 PharmacyDtoMapper 同约定）。 */
    @Mapping(target = "name", source = "medicationName")
    PharmacyOtcCatalogService.ItemView toItemView(PharmacyOtcCatalogRow row);
}

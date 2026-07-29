package com.zhiyu.health.controller.b.mapping;

import com.zhiyu.health.controller.b.HospitalController;
import com.zhiyu.health.entity.Hospital;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** HospitalInput → Hospital：id 由 controller 按 create/update 语义自行设置。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface HospitalInputMapper {

    Hospital toEntity(HospitalController.HospitalInput input);
}

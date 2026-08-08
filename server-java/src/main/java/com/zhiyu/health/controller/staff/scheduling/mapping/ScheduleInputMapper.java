package com.zhiyu.health.controller.staff.scheduling.mapping;

import com.zhiyu.health.controller.staff.scheduling.ScheduleController;
import com.zhiyu.health.entity.scheduling.Schedule;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** ScheduleInput → Schedule：id 由 controller 设置；remainingSlots/isActive 由 service 按业务规则赋值。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ScheduleInputMapper {

    Schedule toEntity(ScheduleController.ScheduleInput input);
}

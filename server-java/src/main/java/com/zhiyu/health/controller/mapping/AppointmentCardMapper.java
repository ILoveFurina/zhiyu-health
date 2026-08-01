package com.zhiyu.health.controller.mapping;

import com.zhiyu.health.controller.AppointmentCardBase;
import com.zhiyu.health.controller.agent.AppointmentToolController;
import com.zhiyu.health.controller.c.AppointmentController;
import com.zhiyu.health.service.AppointmentService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AppointmentCardMapper {

    @Mapping(target = "appointmentId", source = "value.id")
    @Mapping(target = "summaryDisclaimer", source = "summaryDisclaimer")
    AppointmentCardBase toBase(AppointmentService.AppointmentView value, String summaryDisclaimer);

    @Mapping(target = "createdAt", source = "createdAt")
    AppointmentController.AppointmentOut toPatientOut(AppointmentCardBase base, String createdAt);

    @Mapping(target = "summarySent", source = "summarySent")
    @Mapping(target = "notice", source = "notice")
    AppointmentToolController.AppointmentCard toAgentCard(
            AppointmentCardBase base, boolean summarySent, String notice);
}

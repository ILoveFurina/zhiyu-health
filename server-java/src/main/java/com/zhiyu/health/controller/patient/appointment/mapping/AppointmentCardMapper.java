package com.zhiyu.health.controller.patient.appointment.mapping;

import com.zhiyu.health.controller.agent.AppointmentToolController;
import com.zhiyu.health.controller.patient.appointment.AppointmentCardBase;
import com.zhiyu.health.controller.patient.appointment.AppointmentController;
import com.zhiyu.health.service.appointment.AppointmentService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AppointmentCardMapper {

    @Mapping(target = "appointmentId", source = "value.id")
    @Mapping(target = "summaryDisclaimer", source = "summaryDisclaimer")
    AppointmentCardBase toBase(AppointmentService.AppointmentView value, String summaryDisclaimer);

    @Mapping(target = "appointmentId", source = "value.id")
    @Mapping(target = "summaryDisclaimer", source = "summaryDisclaimer")
    @Mapping(target = "createdAt", source = "value.createdAt")
    @Mapping(target = "paymentDeadline", source = "value.paymentDeadline")
    AppointmentController.AppointmentOut toPatientOut(
            AppointmentService.AppointmentView value, String summaryDisclaimer, boolean paymentPayable);

    @Mapping(target = "summarySent", source = "summarySent")
    @Mapping(target = "notice", source = "notice")
    AppointmentToolController.AppointmentCard toAgentCard(AppointmentCardBase base, boolean summarySent, String notice);
}

package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.service.AppointmentService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface AppointmentDtoMapper {

    @Mapping(target = "id", source = "appointment.id")
    @Mapping(target = "scheduleId", source = "appointment.scheduleId")
    @Mapping(target = "doctorId", source = "appointment.doctorId")
    @Mapping(target = "doctorName", source = "appointment.doctorName")
    @Mapping(target = "departmentName", source = "appointment.departmentName")
    @Mapping(target = "scheduleDate", source = "appointment.scheduleDate", qualifiedByName = "dateText")
    @Mapping(target = "timeSlot", source = "appointment.timeSlot", qualifiedByName = "timeSlotText")
    @Mapping(target = "sequenceNumber", source = "appointment.sequenceNumber")
    @Mapping(target = "status", source = "statusLabel")
    @Mapping(target = "registrationFee", source = "appointment.registrationFee")
    @Mapping(target = "paymentStatus", source = "appointment.paymentStatus")
    @Mapping(target = "paymentStatusLabel", source = "paymentStatusLabel")
    @Mapping(target = "conditionSummary", source = "appointment.conditionSummary")
    @Mapping(target = "createdAt", source = "appointment.createdAt", qualifiedByName = "dateTimeText")
    AppointmentService.AppointmentView toView(
            Appointment appointment, String statusLabel, String paymentStatusLabel);

    @Named("dateText")
    default String dateText(LocalDate value) {
        return value == null ? null : value.toString();
    }

    @Named("timeSlotText")
    default String timeSlotText(TimeSlot value) {
        return value == null ? null : value.getValue();
    }

    @Named("dateTimeText")
    default String dateTimeText(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}

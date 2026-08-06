package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.service.PatientMedicalDirectoryService;
import java.time.LocalDate;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PatientMedicalDirectoryDtoMapper {

    @Mapping(target = "doctorId", source = "id")
    PatientMedicalDirectoryService.DoctorView toDoctorView(Doctor doctor);

    @Mapping(target = "scheduleId", source = "id")
    PatientMedicalDirectoryService.ScheduleView toScheduleView(Schedule schedule);

    default String map(LocalDate value) {
        return value == null ? null : value.toString();
    }

    default String map(TimeSlot value) {
        return value == null ? null : value.getValue();
    }
}

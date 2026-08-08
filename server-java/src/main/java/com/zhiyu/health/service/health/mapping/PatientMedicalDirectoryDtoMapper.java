package com.zhiyu.health.service.health.mapping;

import com.zhiyu.health.entity.organization.Doctor;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.entity.scheduling.TimeSlot;
import com.zhiyu.health.service.health.PatientMedicalDirectoryService;
import java.time.LocalDate;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PatientMedicalDirectoryDtoMapper {

    @Mapping(target = "doctorId", source = "id")
    // 表达式须全限定类名：生成的 Impl 在 service.mapping 包，无 PhotoUrls import
    @Mapping(
            target = "photoUrl",
            expression = "java(com.zhiyu.health.service.vision.PhotoUrls.cUrl(doctor.getPhotoUrl()))")
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

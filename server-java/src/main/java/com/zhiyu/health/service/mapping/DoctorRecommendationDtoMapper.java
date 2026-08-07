package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.service.DoctorRecommendationService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DoctorRecommendationDtoMapper {

    @Mapping(target = "doctorId", source = "doctor.id")
    // 表达式须全限定类名：生成的 Impl 在 service.mapping 包，无 PhotoUrls import
    @Mapping(target = "photoUrl", expression = "java(com.zhiyu.health.service.PhotoUrls.cUrl(doctor.getPhotoUrl()))")
    DoctorRecommendationService.DoctorRecommendation toRecommendation(Doctor doctor, int remainingSlots);

    @Mapping(target = "scheduleId", source = "id")
    DoctorRecommendationService.DoctorSlot toSlot(Schedule schedule);
}

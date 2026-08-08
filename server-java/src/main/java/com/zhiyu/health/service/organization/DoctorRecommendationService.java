package com.zhiyu.health.service.organization;

import com.zhiyu.health.entity.organization.Doctor;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.entity.scheduling.TimeSlot;
import com.zhiyu.health.mapper.organization.DoctorMapper;
import com.zhiyu.health.mapper.scheduling.ScheduleMapper;
import com.zhiyu.health.service.organization.mapping.DoctorRecommendationDtoMapper;
import com.zhiyu.health.service.scheduling.SlotWindowGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 为 Agent 业务工具提供医生推荐与可用号源，只读取 PostgreSQL 业务数据。 */
@Service
@RequiredArgsConstructor
public class DoctorRecommendationService {

    private final DoctorMapper doctorMapper;
    private final ScheduleMapper scheduleMapper;
    private final DoctorRecommendationDtoMapper recommendationDtos;
    private final SlotWindowGuard slotWindowGuard;

    public List<DoctorRecommendation> recommendDoctors(String departmentName) {
        List<Schedule> schedules = scheduleMapper.selectAvailableByDepartment(departmentName, LocalDate.now()).stream()
                .filter(schedule -> !slotWindowGuard.isClosed(schedule))
                .toList();
        if (schedules.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> remainingByDoctor = new LinkedHashMap<>();
        schedules.forEach(schedule ->
                remainingByDoctor.merge(schedule.getDoctorId(), schedule.getRemainingSlots(), Integer::sum));
        Map<Long, Doctor> doctorsById = new LinkedHashMap<>();
        doctorMapper.selectByIds(remainingByDoctor.keySet()).forEach(doctor -> doctorsById.put(doctor.getId(), doctor));

        return remainingByDoctor.entrySet().stream()
                .filter(entry -> doctorsById.containsKey(entry.getKey()))
                .map(entry -> recommendationDtos.toRecommendation(doctorsById.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    public List<DoctorSlot> getDoctorSlots(long doctorId) {
        return scheduleMapper.selectAvailableByDoctor(doctorId, LocalDate.now()).stream()
                .filter(schedule -> !slotWindowGuard.isClosed(schedule))
                .map(recommendationDtos::toSlot)
                .toList();
    }

    public record DoctorRecommendation(
            long doctorId,
            String name,
            String title,
            BigDecimal registrationFee,
            String specialty,
            String photoUrl,
            int remainingSlots) {}

    public record DoctorSlot(long scheduleId, LocalDate scheduleDate, TimeSlot timeSlot, int remainingSlots) {}
}

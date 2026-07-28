package com.zhiyu.health.service;

import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为 Agent 业务工具提供医生推荐与可用号源，只读取 PostgreSQL 业务数据。 */
@Service
public class DoctorRecommendationService {

    private final DoctorMapper doctorMapper;
    private final ScheduleMapper scheduleMapper;

    public DoctorRecommendationService(DoctorMapper doctorMapper, ScheduleMapper scheduleMapper) {
        this.doctorMapper = doctorMapper;
        this.scheduleMapper = scheduleMapper;
    }

    public List<DoctorRecommendation> recommendDoctors(String departmentName) {
        List<Schedule> schedules = scheduleMapper.selectAvailableByDepartment(
                departmentName, LocalDate.now());
        if (schedules.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> remainingByDoctor = new LinkedHashMap<>();
        schedules.forEach(schedule -> remainingByDoctor.merge(
                schedule.getDoctorId(), schedule.getRemainingSlots(), Integer::sum));
        Map<Long, Doctor> doctorsById = new LinkedHashMap<>();
        doctorMapper.selectByIds(remainingByDoctor.keySet())
                .forEach(doctor -> doctorsById.put(doctor.getId(), doctor));

        return remainingByDoctor.entrySet().stream()
                .filter(entry -> doctorsById.containsKey(entry.getKey()))
                .map(entry -> toRecommendation(doctorsById.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    public List<DoctorSlot> getDoctorSlots(long doctorId) {
        return scheduleMapper.selectAvailableByDoctor(doctorId, LocalDate.now()).stream()
                .map(schedule -> new DoctorSlot(
                        schedule.getId(), schedule.getScheduleDate(), schedule.getTimeSlot(),
                        schedule.getRemainingSlots()))
                .toList();
    }

    private DoctorRecommendation toRecommendation(Doctor doctor, int remainingSlots) {
        return new DoctorRecommendation(
                doctor.getId(), doctor.getName(), doctor.getTitle(), doctor.getSpecialty(),
                doctor.getPhotoUrl(), remainingSlots);
    }

    public record DoctorRecommendation(
            long doctorId,
            String name,
            String title,
            String specialty,
            String photoUrl,
            int remainingSlots) {
    }

    public record DoctorSlot(
            long scheduleId,
            LocalDate scheduleDate,
            TimeSlot timeSlot,
            int remainingSlots) {
    }
}

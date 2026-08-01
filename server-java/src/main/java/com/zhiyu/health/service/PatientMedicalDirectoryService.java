package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Department;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import com.zhiyu.health.service.mapping.PatientMedicalDirectoryDtoMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** C 端医院、科室、医生与排班的只读逐级目录。 */
@Service
@RequiredArgsConstructor
public class PatientMedicalDirectoryService {

    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;
    private final DoctorMapper doctorMapper;
    private final ScheduleMapper scheduleMapper;
    private final PatientMedicalDirectoryDtoMapper directoryDtos;

    public List<HospitalView> hospitals(Coordinates coordinates) {
        if (coordinates != null) {
            return hospitalMapper.selectNearby(coordinates.longitude(), coordinates.latitude()).stream()
                    .map(directoryDtos::toHospitalView)
                    .toList();
        }
        return hospitalMapper.selectList(Wrappers.<Hospital>lambdaQuery().orderByAsc(Hospital::getId)).stream()
                .map(directoryDtos::toHospitalView)
                .toList();
    }

    public List<DepartmentView> departments(long hospitalId) {
        return departmentMapper
                .selectList(Wrappers.<Department>lambdaQuery()
                        .eq(Department::getHospitalId, hospitalId)
                        .orderByAsc(Department::getName, Department::getId))
                .stream()
                .map(directoryDtos::toDepartmentView)
                .toList();
    }

    public List<DoctorView> doctors(long departmentId) {
        return doctorMapper
                .selectList(Wrappers.<Doctor>lambdaQuery()
                        .eq(Doctor::getDepartmentId, departmentId)
                        .orderByAsc(Doctor::getName, Doctor::getId))
                .stream()
                .map(directoryDtos::toDoctorView)
                .toList();
    }

    public List<ScheduleView> schedules(long doctorId) {
        return scheduleMapper.selectFutureByDoctor(doctorId, LocalDate.now()).stream()
                .map(directoryDtos::toScheduleView)
                .toList();
    }

    public record HospitalView(
            @JsonProperty("hospital_id") long hospitalId,
            String name,
            String level,
            String address,
            Double longitude,
            Double latitude,
            @JsonProperty("distance_km") Double distanceKm) {}

    public record DepartmentView(
            @JsonProperty("department_id") long departmentId,
            @JsonProperty("hospital_id") long hospitalId,
            String name,
            String floor,
            String location) {}

    public record DoctorView(
            @JsonProperty("doctor_id") long doctorId,
            @JsonProperty("department_id") long departmentId,
            String name,
            String title,
            @JsonProperty("registration_fee") BigDecimal registrationFee,
            String specialty,
            @JsonProperty("photo_url") String photoUrl) {}

    public record ScheduleView(
            @JsonProperty("schedule_id") long scheduleId,
            @JsonProperty("doctor_id") long doctorId,
            @JsonProperty("schedule_date") String scheduleDate,
            @JsonProperty("time_slot") String timeSlot,
            @JsonProperty("total_slots") int totalSlots,
            @JsonProperty("remaining_slots") int remainingSlots) {}

    public record Coordinates(double latitude, double longitude) {

        public static Coordinates fromNullable(Double latitude, Double longitude) {
            if (latitude == null && longitude == null) {
                return null;
            }
            if (latitude == null || longitude == null) {
                throw new ApiException(400, "lat 与 lng 必须同时提供");
            }
            return new Coordinates(latitude, longitude);
        }
    }
}

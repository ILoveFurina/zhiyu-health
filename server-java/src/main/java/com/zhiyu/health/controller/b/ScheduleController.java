package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.service.ScheduleService;
import com.zhiyu.health.service.ScheduleCapacityException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/b/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    public record ScheduleInput(
            @NotNull Long doctorId,
            @NotNull LocalDate scheduleDate,
            @NotNull TimeSlot timeSlot,
            @NotNull @Positive Integer totalSlots) {
    }

    @GetMapping
    public List<Schedule> list(HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        return scheduleService.listSchedules();
    }

    @GetMapping("/{id}")
    public Schedule get(@PathVariable long id, HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        Schedule schedule = scheduleService.getSchedule(id);
        if (schedule == null) {
            throw new ApiException(404, "排班不存在");
        }
        return schedule;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Schedule create(@Valid @RequestBody ScheduleInput input, HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        Schedule created = scheduleService.createSchedule(toEntity(input));
        if (created == null) {
            throw new ApiException(404, "医生不存在");
        }
        return created;
    }

    @PutMapping("/{id}")
    public Schedule update(@PathVariable long id,
                           @Valid @RequestBody ScheduleInput input,
                           HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        Schedule changes = toEntity(input);
        changes.setId(id);
        try {
            Schedule updated = scheduleService.updateSchedule(changes);
            if (updated == null) {
                throw new ApiException(404, "排班或医生不存在");
            }
            return updated;
        } catch (ScheduleCapacityException exception) {
            throw new ApiException(409, exception.getMessage());
        }
    }

    @PatchMapping("/{id}/disable")
    public Schedule disable(@PathVariable long id, HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        Schedule disabled = scheduleService.disableSchedule(id);
        if (disabled == null) {
            throw new ApiException(404, "排班不存在");
        }
        return disabled;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, HttpServletRequest request) {
        disable(id, request);
    }

    private Schedule toEntity(ScheduleInput input) {
        Schedule schedule = new Schedule();
        schedule.setDoctorId(input.doctorId());
        schedule.setScheduleDate(input.scheduleDate());
        schedule.setTimeSlot(input.timeSlot());
        schedule.setTotalSlots(input.totalSlots());
        return schedule;
    }
}

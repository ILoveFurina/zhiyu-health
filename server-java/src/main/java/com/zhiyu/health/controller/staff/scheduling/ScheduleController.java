package com.zhiyu.health.controller.staff.scheduling;

import com.zhiyu.health.controller.staff.scheduling.mapping.ScheduleInputMapper;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.entity.scheduling.TimeSlot;
import com.zhiyu.health.service.scheduling.ScheduleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

/** 排班管理：404/409 由 service 抛 ApiException */
@RestController
@RequestMapping("/api/b/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ScheduleInputMapper scheduleInputMapper;

    public record ScheduleInput(
            @NotNull Long doctorId,
            @NotNull LocalDate scheduleDate,
            @NotNull TimeSlot timeSlot,
            @NotNull @Positive Integer totalSlots) {}

    @GetMapping
    public List<Schedule> list() {
        return scheduleService.listSchedules();
    }

    @GetMapping("/{id}")
    public Schedule get(@PathVariable long id) {
        return scheduleService.getSchedule(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Schedule create(@Valid @RequestBody ScheduleInput input) {
        return scheduleService.createSchedule(scheduleInputMapper.toEntity(input));
    }

    @PutMapping("/{id}")
    public Schedule update(@PathVariable long id, @Valid @RequestBody ScheduleInput input) {
        // 路径 id 覆盖 body 中可能缺失或不一致的 id，避免误改其它排班。
        Schedule changes = scheduleInputMapper.toEntity(input);
        changes.setId(id);
        return scheduleService.updateSchedule(changes);
    }

    @PatchMapping("/{id}/disable")
    public Schedule disable(@PathVariable long id) {
        return scheduleService.disableSchedule(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        // DELETE 语义实为逻辑停诊（disable），不硬删：号源记录需保留用于历史对账与已挂号单回溯，
        // 真正的停诊经 ScheduleRequestService 审核流，此处仅作为管理员快捷停诊入口。
        disable(id);
    }
}

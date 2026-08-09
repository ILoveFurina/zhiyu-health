package com.zhiyu.health.controller.staff.scheduling;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.entity.scheduling.ScheduleRequest;
import com.zhiyu.health.entity.scheduling.TimeSlot;
import com.zhiyu.health.service.scheduling.ScheduleRequestService;
import com.zhiyu.health.service.scheduling.ScheduleRequestService.ScheduleRequestItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医生排班申请：路径在 /api/b/reception/** 豁免区，doctor 角色由 service 层 requireDoctor 校验。
 * 医生只能为自己排班，doctorId 由 service 校验与登录身份一致。
 */
@RestController
@RequestMapping("/api/b/reception")
@RequiredArgsConstructor
public class ScheduleRequestDoctorController {

    private final ScheduleRequestService scheduleRequestService;

    /** 单条申请项：日期 + 时段 + 号源数。 */
    public record ScheduleItemInput(
            @JsonProperty("schedule_date") @NotNull LocalDate scheduleDate,
            @JsonProperty("time_slot") @NotNull TimeSlot timeSlot,
            @JsonProperty("total_slots") @NotNull @Positive Integer totalSlots) {}

    /** 批量提交入参：doctor_id 须与登录医生一致，items 笛卡尔积展开为多条申请。 */
    public record SubmitInput(
            @JsonProperty("doctor_id") @NotNull Long doctorId,
            @NotEmpty @Size(max = 50) List<@Valid ScheduleItemInput> items) {}

    /** 排班变更申请入参：modify 传 new_total_slots，disable 不传。 */
    public record ChangeInput(@NotBlank String action, @JsonProperty("new_total_slots") Integer newTotalSlots) {}

    /** 医生查看自己未来排班（排班表页面用）。 */
    @GetMapping("/schedule-table")
    public List<Schedule> scheduleTable(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return scheduleRequestService.listMySchedule(staffId);
    }

    /** 批量提交新增排班申请：前端按日期范围×时段展开为 items 传入。 */
    @PostMapping("/schedule-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ScheduleRequest> submit(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId, @Valid @RequestBody SubmitInput input) {
        List<ScheduleRequestItem> items = input.items().stream()
                .map(i -> new ScheduleRequestItem(i.scheduleDate(), i.timeSlot(), i.totalSlots()))
                .toList();
        return scheduleRequestService.submit(staffId, input.doctorId(), items);
    }

    /** 医生对已有排班发起调整号源/停诊申请，需管理员审核。 */
    @PostMapping("/schedules/{targetScheduleId}/change-request")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleRequest submitChange(
            @PathVariable long targetScheduleId,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId,
            @Valid @RequestBody ChangeInput input) {
        return scheduleRequestService.submitChange(staffId, targetScheduleId, input.action(), input.newTotalSlots());
    }

    /** 医生查看自己的排班申请（含全部状态）。 */
    @GetMapping("/schedule-requests/mine")
    public List<ScheduleRequest> mine(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return scheduleRequestService.listMine(staffId);
    }
}

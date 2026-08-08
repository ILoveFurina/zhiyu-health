package com.zhiyu.health.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.entity.ScheduleRequest;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.mapper.ScheduleMapper;
import com.zhiyu.health.mapper.ScheduleRequestMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 排班申请审核闭环：医生批量提交申请（限当天起 max_days_ahead 天内），
 * 管理员审核通过后落盘为 schedules 行（含 SlotAccounting 的 Redis/PG 双写一致性），C 端即可见。
 * 支持三种操作类型：CREATE（新增排班）、MODIFY（调整已有排班号源数）、DISABLE（停诊）。
 * 并发审核靠条件更新（WHERE status='PENDING'）保证只有一个决定生效。
 */
@Service
@RequiredArgsConstructor
public class ScheduleRequestService extends ServiceImpl<ScheduleRequestMapper, ScheduleRequest> {

    private final StaffUserMapper staffUserMapper;
    private final ScheduleService scheduleService;
    private final ScheduleMapper scheduleMapper;
    private final TransactionTemplate transactionTemplate;
    private final Contracts contracts;

    /** 医生批量提交新增排班申请。每条校验日期/号源/时段合法性，逐条 insert。 */
    public List<ScheduleRequest> submit(long staffId, long doctorId, List<ScheduleRequestItem> items) {
        long staffDoctorId = requireDoctor(staffId);
        if (staffDoctorId != doctorId) {
            // 医生只能为自己排班，doctorId 不匹配拒绝，防止越权代排。
            throw new ApiException(403, "只能为自己提交排班申请");
        }
        if (items == null || items.isEmpty()) {
            throw new ApiException(400, "排班申请不能为空");
        }
        LocalDate today = LocalDate.now();
        LocalDate maxDate = today.plusDays(contracts.scheduleRequestFlow().maxDaysAhead());
        int maxSlots = contracts.scheduleRequestFlow().maxTotalSlots();
        Set<String> validSlots =
                Set.copyOf(contracts.scheduleRequestFlow().timeSlots().values());
        // 展开为实体并逐条校验，任一不合法整体拒绝（不留半批已提交的中间态）。
        List<ScheduleRequest> requests = items.stream()
                .map(item -> {
                    validateScheduleFields(
                            item.scheduleDate(),
                            item.timeSlot(),
                            item.totalSlots(),
                            today,
                            maxDate,
                            maxSlots,
                            validSlots);
                    // 查重：同医生同日同时段已有活跃排班或待审核的新增申请则拒绝，防止重复排班。
                    checkDuplicateCreate(
                            doctorId, item.scheduleDate(), item.timeSlot().getValue());
                    ScheduleRequest req = newRequest(doctorId, staffId);
                    req.setScheduleDate(item.scheduleDate());
                    req.setTimeSlot(item.timeSlot());
                    req.setTotalSlots(item.totalSlots());
                    req.setAction(action("create"));
                    return req;
                })
                .toList();
        // 逐条 insert 而非 saveBatch：单测可用 mock baseMapper 验证，且演示量级无批量性能压力。
        requests.forEach(baseMapper::insert);
        return requests;
    }

    /**
     * 医生对已有排班发起调整号源申请（MODIFY）或停诊申请（DISABLE）。
     * targetScheduleId 指向被操作的 schedules 行；校验排班归属当前医生且日期在未来。
     */
    public ScheduleRequest submitChange(long staffId, long targetScheduleId, String actionName, Integer newTotalSlots) {
        long doctorId = requireDoctor(staffId);
        Schedule target = scheduleMapper.selectById(targetScheduleId);
        if (target == null) {
            throw new ApiException(404, "排班不存在");
        }
        String actionValue = action(actionName);
        if (actionValue == null) {
            throw new ApiException(400, "操作类型无效");
        }
        // DISABLE 只能对可出诊排班发起；ENABLE 只能对已停诊排班发起（互为逆操作）。
        if (action("disable").equals(actionValue) && !Boolean.TRUE.equals(target.getIsActive())) {
            throw new ApiException(404, "排班不存在或已停用");
        }
        if (action("enable").equals(actionValue) && Boolean.TRUE.equals(target.getIsActive())) {
            throw new ApiException(400, "排班已处于可出诊状态，无需恢复");
        }
        if (!target.getDoctorId().equals(doctorId)) {
            // 只能调整自己的排班，防止越权操作他人排班。
            throw new ApiException(403, "只能调整自己的排班");
        }
        if (!target.getScheduleDate().isAfter(LocalDate.now())) {
            throw new ApiException(400, "只能调整未来日期的排班");
        }
        int maxSlots = contracts.scheduleRequestFlow().maxTotalSlots();
        ScheduleRequest req = newRequest(doctorId, staffId);
        req.setScheduleDate(target.getScheduleDate());
        req.setTimeSlot(target.getTimeSlot());
        req.setAction(actionValue);
        req.setTargetScheduleId(targetScheduleId);
        if (action("modify").equals(actionValue)) {
            if (newTotalSlots == null || newTotalSlots < 1 || newTotalSlots > maxSlots) {
                throw new ApiException(400, "号源数量必须在 1-" + maxSlots + " 之间");
            }
            req.setTotalSlots(newTotalSlots);
        } else {
            // DISABLE / ENABLE：号源数沿用原排班，仅作记录用
            req.setTotalSlots(target.getTotalSlots());
        }
        baseMapper.insert(req);
        return req;
    }

    /** 医生查看自己的排班申请。 */
    public List<ScheduleRequest> listMine(long staffId) {
        long doctorId = requireDoctor(staffId);
        return baseMapper.selectByDoctor(doctorId);
    }

    /** 医生查看自己未来排班（排班表页面用）。 */
    public List<Schedule> listMySchedule(long staffId) {
        long doctorId = requireDoctor(staffId);
        return scheduleMapper.selectFutureByDoctor(doctorId, LocalDate.now());
    }

    /** 管理员查看待审核列表（联查医生/科室/职称）。status 默认 PENDING。 */
    public List<ScheduleRequest> listForReview(String status) {
        String normalized = status == null || status.isBlank() ? status("pending") : status;
        if (!contracts.scheduleRequestFlow().statuses().containsValue(normalized)) {
            throw new ApiException(400, "审核状态无效");
        }
        return baseMapper.selectForReview(normalized);
    }

    /**
     * 管理员审核排班申请。通过则按 action 类型落盘：
     * CREATE -> createSchedule 新建行；MODIFY -> updateSchedule 调整号源；DISABLE -> disableSchedule 停诊。
     * 驳回需填原因。条件更新保证并发安全，重复审核返 409。
     */
    public ScheduleRequest review(long reviewerId, long id, String decision, String reason) {
        ScheduleRequest request = baseMapper.selectDetailedById(id);
        if (request == null) {
            throw new ApiException(404, "排班申请不存在");
        }
        if (!status("pending").equals(request.getStatus())) {
            throw new ApiException(409, "排班申请已审核");
        }
        String target;
        Long scheduleId = null;
        if (decision("approve").equals(decision)) {
            target = status("approved");
            scheduleId = applyApprovedAction(request);
        } else if (decision("reject").equals(decision)) {
            if (reason == null || reason.isBlank()) {
                throw new ApiException(400, "驳回时必须填写原因");
            }
            target = status("rejected");
        } else {
            throw new ApiException(400, "审核决定无效");
        }
        String trimmedReason = trimToNull(reason);
        String reviewTarget = target;
        Long reviewScheduleId = scheduleId;
        return transactionTemplate.execute(tx -> {
            // 条件更新保证并发审核只有一个决定生效，避免先通过后被另一请求覆盖为驳回。
            if (baseMapper.review(id, reviewTarget, trimmedReason, reviewerId, reviewScheduleId, status("pending"))
                    != 1) {
                throw new ApiException(409, "排班申请已审核");
            }
            return baseMapper.selectDetailedById(id);
        });
    }

    /**
     * 审核通过时按 action 类型执行实际排班操作，返回关联的 schedule_id。
     * CREATE 复用 createSchedule（含 SlotAccounting 双写初始化）；
     * MODIFY 复用 updateSchedule（容量调整 + Redis 计数对账）；
     * DISABLE 复用 disableSchedule（仅更 is_active，不触碰 remaining_slots）；
     * ENABLE 复用 enableSchedule（恢复 is_active=true，号源保持冻结值）。
     */
    private Long applyApprovedAction(ScheduleRequest request) {
        if (action("create").equals(request.getAction())) {
            Schedule created = scheduleService.createSchedule(buildSchedule(request));
            return created.getId();
        }
        if (action("modify").equals(request.getAction())) {
            Schedule changes = buildSchedule(request);
            changes.setId(request.getTargetScheduleId());
            scheduleService.updateSchedule(changes);
            return request.getTargetScheduleId();
        }
        if (action("disable").equals(request.getAction())) {
            scheduleService.disableSchedule(request.getTargetScheduleId());
            return request.getTargetScheduleId();
        }
        if (action("enable").equals(request.getAction())) {
            scheduleService.enableSchedule(request.getTargetScheduleId());
            return request.getTargetScheduleId();
        }
        throw new ApiException(400, "操作类型无效");
    }

    private void validateScheduleFields(
            LocalDate scheduleDate,
            TimeSlot timeSlot,
            Integer totalSlots,
            LocalDate today,
            LocalDate maxDate,
            int maxSlots,
            Set<String> validSlots) {
        if (scheduleDate == null) {
            throw new ApiException(400, "排班日期不能为空");
        }
        if (scheduleDate.isBefore(today) || scheduleDate.isAfter(maxDate)) {
            throw new ApiException(
                    400, "排班日期必须在当天起 " + contracts.scheduleRequestFlow().maxDaysAhead() + " 天内");
        }
        if (timeSlot == null || !validSlots.contains(timeSlot.getValue())) {
            throw new ApiException(400, "排班时段无效");
        }
        if (totalSlots == null || totalSlots < 1 || totalSlots > maxSlots) {
            throw new ApiException(400, "号源数量必须在 1-" + maxSlots + " 之间");
        }
    }

    /**
     * 排班申请查重：同医生同日同时段已有活跃排班或待审核的新增申请则拒绝。
     * 避免 CREATE 审核通过后落出重复排班行，也避免重复提交待审核申请。
     */
    private void checkDuplicateCreate(long doctorId, LocalDate scheduleDate, String timeSlotValue) {
        if (scheduleMapper.countActiveByDoctorDateSlot(doctorId, scheduleDate, timeSlotValue) > 0) {
            throw new ApiException(400, "该日期该时段已有排班，不可重复申请");
        }
        if (baseMapper.countPendingCreateByDoctorDateSlot(doctorId, scheduleDate, timeSlotValue) > 0) {
            throw new ApiException(400, "该日期该时段已有待审核的排班申请");
        }
    }

    private ScheduleRequest newRequest(long doctorId, long staffId) {
        ScheduleRequest req = new ScheduleRequest();
        req.setDoctorId(doctorId);
        req.setStatus(status("pending"));
        req.setSubmittedBy(staffId);
        return req;
    }

    /** 从申请构建 Schedule 实体（不含 id/remainingSlots/isActive，由 service 赋值）。 */
    private Schedule buildSchedule(ScheduleRequest request) {
        Schedule schedule = new Schedule();
        schedule.setDoctorId(request.getDoctorId());
        schedule.setScheduleDate(request.getScheduleDate());
        schedule.setTimeSlot(request.getTimeSlot());
        schedule.setTotalSlots(request.getTotalSlots());
        return schedule;
    }

    private long requireDoctor(long staffId) {
        StaffUser staff = staffUserMapper.selectById(staffId);
        if (staff == null || !StaffUser.ROLE_DOCTOR.equals(staff.getRole()) || staff.getDoctorId() == null) {
            throw new ApiException(403, "仅医生可操作");
        }
        return staff.getDoctorId();
    }

    private String status(String name) {
        return contracts.scheduleRequestFlow().statuses().get(name);
    }

    private String decision(String name) {
        return contracts.scheduleRequestFlow().decisions().get(name);
    }

    private String action(String name) {
        return contracts.scheduleRequestFlow().actions().get(name);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 医生提交的单条新增排班申请项（日期 + 时段 + 号源数）。 */
    public record ScheduleRequestItem(LocalDate scheduleDate, TimeSlot timeSlot, Integer totalSlots) {}
}

package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.entity.scheduling.ScheduleRequest;
import com.zhiyu.health.entity.scheduling.TimeSlot;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.scheduling.ScheduleMapper;
import com.zhiyu.health.mapper.scheduling.ScheduleRequestMapper;
import com.zhiyu.health.service.scheduling.ScheduleRequestService;
import com.zhiyu.health.service.scheduling.ScheduleService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 排班申请审核闭环单测：提交校验、审核通过落盘、驳回需原因、并发审核 409。
 * Contracts 用真实加载（从仓库根 contracts/ 读 JSON），保证契约值与生产一致。
 */
class ScheduleRequestServiceTest {

    private final StaffUserMapper staffUserMapper = mock(StaffUserMapper.class);
    private final ScheduleService scheduleService = mock(ScheduleService.class);
    private final ScheduleRequestMapper scheduleRequestMapper = mock(ScheduleRequestMapper.class);
    private final ScheduleMapper scheduleMapper = mock(ScheduleMapper.class);
    private final Contracts contracts = Contracts.load(Contracts.resolveDir());
    private final TransactionTemplate transactionTemplate = immediateTransaction();

    private ScheduleRequestService service;

    @BeforeEach
    void setUp() {
        service = new ScheduleRequestService(
                staffUserMapper, scheduleService, scheduleMapper, transactionTemplate, contracts);
        ReflectionTestUtils.setField(service, "baseMapper", scheduleRequestMapper);
    }

    @Test
    void submitCreatesRequestsForValidItems() {
        StaffUser doctor = staffUser(10L, 5L);
        when(staffUserMapper.selectById(10L)).thenReturn(doctor);
        when(scheduleMapper.countActiveByDoctorDateSlot(eq(5L), any(LocalDate.class), any(String.class)))
                .thenReturn(0);
        when(scheduleRequestMapper.countPendingCreateByDoctorDateSlot(eq(5L), any(LocalDate.class), any(String.class)))
                .thenReturn(0);
        LocalDate today = LocalDate.now();
        List<ScheduleRequestService.ScheduleRequestItem> items = List.of(
                new ScheduleRequestService.ScheduleRequestItem(today, TimeSlot.MORNING, 10),
                new ScheduleRequestService.ScheduleRequestItem(today.plusDays(1), TimeSlot.AFTERNOON, 15));

        List<ScheduleRequest> result = service.submit(10L, 5L, items);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(result.get(0).getDoctorId()).isEqualTo(5L);
        assertThat(result.get(0).getSubmittedBy()).isEqualTo(10L);
        verify(scheduleRequestMapper, org.mockito.Mockito.times(2)).insert(any(ScheduleRequest.class));
    }

    @Test
    void submitRejectsNonDoctor() {
        StaffUser admin = new StaffUser();
        admin.setRole(StaffUser.ROLE_ADMIN);
        when(staffUserMapper.selectById(1L)).thenReturn(admin);

        assertThatThrownBy(() -> service.submit(
                        1L,
                        5L,
                        List.of(new ScheduleRequestService.ScheduleRequestItem(LocalDate.now(), TimeSlot.MORNING, 10))))
                .isInstanceOf(ApiException.class)
                .hasMessage("仅医生可操作");
    }

    @Test
    void submitRejectsDoctorMismatch() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));

        assertThatThrownBy(() -> service.submit(
                        10L,
                        99L,
                        List.of(new ScheduleRequestService.ScheduleRequestItem(LocalDate.now(), TimeSlot.MORNING, 10))))
                .isInstanceOf(ApiException.class)
                .hasMessage("只能为自己提交排班申请");
    }

    @Test
    void submitRejectsDateBeyondMaxDaysAhead() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        LocalDate tooFar =
                LocalDate.now().plusDays(contracts.scheduleRequestFlow().maxDaysAhead() + 1);

        assertThatThrownBy(() -> service.submit(
                        10L, 5L, List.of(new ScheduleRequestService.ScheduleRequestItem(tooFar, TimeSlot.MORNING, 10))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("排班日期必须在当天起")
                .hasMessageContaining("14 天内");
    }

    @Test
    void submitRejectsPastDate() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));

        assertThatThrownBy(() -> service.submit(
                        10L,
                        5L,
                        List.of(new ScheduleRequestService.ScheduleRequestItem(
                                LocalDate.now().minusDays(1), TimeSlot.MORNING, 10))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("排班日期必须在当天起");
    }

    @Test
    void submitRejectsTotalSlotsOutOfRange() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));

        assertThatThrownBy(() -> service.submit(
                        10L,
                        5L,
                        List.of(new ScheduleRequestService.ScheduleRequestItem(LocalDate.now(), TimeSlot.MORNING, 0))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("号源数量必须在 1-50 之间");
    }

    @Test
    void submitRejectsEmptyItems() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));

        assertThatThrownBy(() -> service.submit(10L, 5L, List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessage("排班申请不能为空");
    }

    @Test
    void reviewApproveCreatesScheduleAndBackfillsScheduleId() {
        ScheduleRequest pending = request(1L, "PENDING");
        when(scheduleRequestMapper.selectDetailedById(1L))
                .thenReturn(pending, requestWithSchedule(1L, "APPROVED", 100L));
        Schedule created = new Schedule();
        created.setId(100L);
        when(scheduleService.createSchedule(any(Schedule.class))).thenReturn(created);
        when(scheduleRequestMapper.review(eq(1L), eq("APPROVED"), any(), eq(20L), eq(100L), eq("PENDING")))
                .thenReturn(1);

        ScheduleRequest result = service.review(20L, 1L, "APPROVE", null);

        assertThat(result.getStatus()).isEqualTo("APPROVED");
        assertThat(result.getScheduleId()).isEqualTo(100L);
        verify(scheduleService).createSchedule(any(Schedule.class));
    }

    @Test
    void reviewRejectRequiresReason() {
        ScheduleRequest pending = request(1L, "PENDING");
        when(scheduleRequestMapper.selectDetailedById(1L)).thenReturn(pending);

        assertThatThrownBy(() -> service.review(20L, 1L, "REJECT", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("驳回时必须填写原因");
        verify(scheduleRequestMapper, never()).review(anyLong(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void reviewRejectsAlreadyReviewedRequest() {
        ScheduleRequest approved = request(1L, "APPROVED");
        when(scheduleRequestMapper.selectDetailedById(1L)).thenReturn(approved);

        assertThatThrownBy(() -> service.review(20L, 1L, "APPROVE", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("排班申请已审核");
    }

    @Test
    void reviewRejectsInvalidDecision() {
        ScheduleRequest pending = request(1L, "PENDING");
        when(scheduleRequestMapper.selectDetailedById(1L)).thenReturn(pending);

        assertThatThrownBy(() -> service.review(20L, 1L, "INVALID", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("审核决定无效");
    }

    @Test
    void reviewReturns409OnConcurrentApproval() {
        ScheduleRequest pending = request(1L, "PENDING");
        when(scheduleRequestMapper.selectDetailedById(1L)).thenReturn(pending);
        Schedule created = new Schedule();
        created.setId(100L);
        when(scheduleService.createSchedule(any(Schedule.class))).thenReturn(created);
        // 条件更新返回 0 行：模拟并发下另一请求已先审核
        when(scheduleRequestMapper.review(eq(1L), eq("APPROVED"), any(), eq(20L), eq(100L), eq("PENDING")))
                .thenReturn(0);

        assertThatThrownBy(() -> service.review(20L, 1L, "APPROVE", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("排班申请已审核");
        // 修复后号源变更（createSchedule）与审核 CAS 在同一事务内：transactionTemplate 只执行一次，
        // 生产环境下 CAS 失败抛 409 触发事务回滚，withAdjustment/withInitialization 补偿 Redis。
        verify(transactionTemplate, times(1)).execute(any());
    }

    @Test
    void reviewApproveApplyAndCasShareOneTransaction() {
        // 回归测试（号源 Redis 漂移根因修复）：applyApprovedAction 必须与 baseMapper.review 的 CAS
        // 在同一个 transactionTemplate.execute 内，而非分离的两个事务。否则 CAS 失败时号源变更
        // 已独立提交无法回滚，导致 Redis 计数漂移（PG 有 CAS 守卫挡住、Redis 无）。
        ScheduleRequest modifyReq = request(1L, "PENDING");
        modifyReq.setAction("MODIFY");
        modifyReq.setTargetScheduleId(50L);
        modifyReq.setTotalSlots(20);
        when(scheduleRequestMapper.selectDetailedById(1L)).thenReturn(modifyReq, modifyReq);
        when(scheduleRequestMapper.review(eq(1L), eq("APPROVED"), any(), eq(20L), eq(50L), eq("PENDING")))
                .thenReturn(1);

        service.review(20L, 1L, "APPROVE", null);

        // 号源变更与 CAS 在同一事务：transactionTemplate 只执行一次
        verify(transactionTemplate, times(1)).execute(any());
        verify(scheduleService).updateSchedule(any(Schedule.class));
    }

    @Test
    void reviewReturns404WhenRequestMissing() {
        when(scheduleRequestMapper.selectDetailedById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.review(20L, 99L, "APPROVE", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("排班申请不存在");
    }

    @Test
    void listMineReturnsDoctorRequests() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        when(scheduleRequestMapper.selectByDoctor(5L)).thenReturn(List.of(request(1L, "PENDING")));

        List<ScheduleRequest> result = service.listMine(10L);

        assertThat(result).hasSize(1);
        verify(scheduleRequestMapper).selectByDoctor(5L);
    }

    @Test
    void listForReviewDefaultsToPending() {
        when(scheduleRequestMapper.selectForReview("PENDING")).thenReturn(List.of(request(1L, "PENDING")));

        List<ScheduleRequest> result = service.listForReview(null);

        assertThat(result).hasSize(1);
        verify(scheduleRequestMapper).selectForReview("PENDING");
    }

    @Test
    void listForReviewRejectsInvalidStatus() {
        assertThatThrownBy(() -> service.listForReview("INVALID"))
                .isInstanceOf(ApiException.class)
                .hasMessage("审核状态无效");
    }

    @Test
    void submitChangeModifyCreatesRequestWithTargetScheduleId() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        Schedule target = new Schedule();
        target.setId(50L);
        target.setDoctorId(5L);
        target.setScheduleDate(LocalDate.now().plusDays(2));
        target.setTimeSlot(TimeSlot.AFTERNOON);
        target.setTotalSlots(10);
        target.setIsActive(true);
        when(scheduleMapper.selectById(50L)).thenReturn(target);

        ScheduleRequest result = service.submitChange(10L, 50L, "modify", 20);

        assertThat(result.getAction()).isEqualTo("MODIFY");
        assertThat(result.getTargetScheduleId()).isEqualTo(50L);
        assertThat(result.getTotalSlots()).isEqualTo(20);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(scheduleRequestMapper).insert(any(ScheduleRequest.class));
    }

    @Test
    void submitChangeDisableCreatesRequestWithTargetScheduleId() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        Schedule target = new Schedule();
        target.setId(50L);
        target.setDoctorId(5L);
        target.setScheduleDate(LocalDate.now().plusDays(2));
        target.setTimeSlot(TimeSlot.MORNING);
        target.setTotalSlots(10);
        target.setIsActive(true);
        when(scheduleMapper.selectById(50L)).thenReturn(target);

        ScheduleRequest result = service.submitChange(10L, 50L, "disable", null);

        assertThat(result.getAction()).isEqualTo("DISABLE");
        assertThat(result.getTargetScheduleId()).isEqualTo(50L);
        assertThat(result.getTotalSlots()).isEqualTo(10);
    }

    @Test
    void submitChangeRejectsOthersDoctorSchedule() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        Schedule target = new Schedule();
        target.setId(50L);
        target.setDoctorId(99L);
        target.setScheduleDate(LocalDate.now().plusDays(2));
        target.setIsActive(true);
        when(scheduleMapper.selectById(50L)).thenReturn(target);

        assertThatThrownBy(() -> service.submitChange(10L, 50L, "disable", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("只能调整自己的排班");
    }

    @Test
    void submitChangeRejectsPastDateSchedule() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        Schedule target = new Schedule();
        target.setId(50L);
        target.setDoctorId(5L);
        target.setScheduleDate(LocalDate.now().minusDays(1));
        target.setIsActive(true);
        when(scheduleMapper.selectById(50L)).thenReturn(target);

        assertThatThrownBy(() -> service.submitChange(10L, 50L, "disable", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("只能调整未来日期的排班");
    }

    @Test
    void reviewApproveModifyCallsUpdateSchedule() {
        ScheduleRequest modifyReq = request(1L, "PENDING");
        modifyReq.setAction("MODIFY");
        modifyReq.setTargetScheduleId(50L);
        modifyReq.setTotalSlots(20);
        when(scheduleRequestMapper.selectDetailedById(1L)).thenReturn(modifyReq, modifyReq);
        when(scheduleRequestMapper.review(eq(1L), eq("APPROVED"), any(), eq(20L), eq(50L), eq("PENDING")))
                .thenReturn(1);

        service.review(20L, 1L, "APPROVE", null);

        verify(scheduleService).updateSchedule(any(Schedule.class));
        verify(scheduleService, never()).createSchedule(any(Schedule.class));
    }

    @Test
    void reviewApproveDisableCallsDisableSchedule() {
        ScheduleRequest disableReq = request(1L, "PENDING");
        disableReq.setAction("DISABLE");
        disableReq.setTargetScheduleId(50L);
        when(scheduleRequestMapper.selectDetailedById(1L)).thenReturn(disableReq, disableReq);
        when(scheduleRequestMapper.review(eq(1L), eq("APPROVED"), any(), eq(20L), eq(50L), eq("PENDING")))
                .thenReturn(1);

        service.review(20L, 1L, "APPROVE", null);

        verify(scheduleService).disableSchedule(50L);
    }

    @Test
    void submitChangeEnableCreatesRequestForDisabledSchedule() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        Schedule target = new Schedule();
        target.setId(50L);
        target.setDoctorId(5L);
        target.setScheduleDate(LocalDate.now().plusDays(2));
        target.setTimeSlot(TimeSlot.MORNING);
        target.setTotalSlots(10);
        target.setIsActive(false);
        when(scheduleMapper.selectById(50L)).thenReturn(target);

        ScheduleRequest result = service.submitChange(10L, 50L, "enable", null);

        assertThat(result.getAction()).isEqualTo("ENABLE");
        assertThat(result.getTargetScheduleId()).isEqualTo(50L);
        assertThat(result.getTotalSlots()).isEqualTo(10);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(scheduleRequestMapper).insert(any(ScheduleRequest.class));
    }

    @Test
    void submitChangeEnableRejectsActiveSchedule() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        Schedule target = new Schedule();
        target.setId(50L);
        target.setDoctorId(5L);
        target.setScheduleDate(LocalDate.now().plusDays(2));
        target.setIsActive(true);
        when(scheduleMapper.selectById(50L)).thenReturn(target);

        assertThatThrownBy(() -> service.submitChange(10L, 50L, "enable", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("排班已处于可出诊状态，无需恢复");
    }

    @Test
    void reviewApproveEnableCallsEnableSchedule() {
        ScheduleRequest enableReq = request(1L, "PENDING");
        enableReq.setAction("ENABLE");
        enableReq.setTargetScheduleId(50L);
        when(scheduleRequestMapper.selectDetailedById(1L)).thenReturn(enableReq, enableReq);
        when(scheduleRequestMapper.review(eq(1L), eq("APPROVED"), any(), eq(20L), eq(50L), eq("PENDING")))
                .thenReturn(1);

        service.review(20L, 1L, "APPROVE", null);

        verify(scheduleService).enableSchedule(50L);
    }

    @Test
    void submitRejectsDuplicateActiveSchedule() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        when(scheduleMapper.countActiveByDoctorDateSlot(eq(5L), any(LocalDate.class), eq("上午")))
                .thenReturn(1);

        assertThatThrownBy(() -> service.submit(
                        10L,
                        5L,
                        List.of(new ScheduleRequestService.ScheduleRequestItem(LocalDate.now(), TimeSlot.MORNING, 10))))
                .isInstanceOf(ApiException.class)
                .hasMessage("该日期该时段已有排班，不可重复申请");
    }

    @Test
    void submitRejectsDuplicatePendingRequest() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        when(scheduleMapper.countActiveByDoctorDateSlot(eq(5L), any(LocalDate.class), eq("上午")))
                .thenReturn(0);
        when(scheduleRequestMapper.countPendingCreateByDoctorDateSlot(eq(5L), any(LocalDate.class), eq("上午")))
                .thenReturn(1);

        assertThatThrownBy(() -> service.submit(
                        10L,
                        5L,
                        List.of(new ScheduleRequestService.ScheduleRequestItem(LocalDate.now(), TimeSlot.MORNING, 10))))
                .isInstanceOf(ApiException.class)
                .hasMessage("该日期该时段已有待审核的排班申请");
    }

    @Test
    void listMyScheduleReturnsDoctorFutureSchedules() {
        when(staffUserMapper.selectById(10L)).thenReturn(staffUser(10L, 5L));
        Schedule s = new Schedule();
        s.setId(1L);
        s.setDoctorId(5L);
        when(scheduleMapper.selectFutureByDoctor(eq(5L), any(LocalDate.class))).thenReturn(List.of(s));

        List<Schedule> result = service.listMySchedule(10L);

        assertThat(result).hasSize(1);
        verify(scheduleMapper).selectFutureByDoctor(eq(5L), any(LocalDate.class));
    }

    private StaffUser staffUser(long id, long doctorId) {
        StaffUser staff = new StaffUser();
        staff.setId(id);
        staff.setRole(StaffUser.ROLE_DOCTOR);
        staff.setDoctorId(doctorId);
        return staff;
    }

    private ScheduleRequest request(long id, String status) {
        ScheduleRequest req = new ScheduleRequest();
        req.setId(id);
        req.setDoctorId(5L);
        req.setScheduleDate(LocalDate.now().plusDays(1));
        req.setTimeSlot(TimeSlot.MORNING);
        req.setTotalSlots(10);
        req.setAction("CREATE");
        req.setStatus(status);
        req.setSubmittedBy(10L);
        return req;
    }

    private ScheduleRequest requestWithSchedule(long id, String status, long scheduleId) {
        ScheduleRequest req = request(id, status);
        req.setScheduleId(scheduleId);
        req.setReviewedBy(20L);
        return req;
    }

    private TransactionTemplate immediateTransaction() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return template;
    }
}

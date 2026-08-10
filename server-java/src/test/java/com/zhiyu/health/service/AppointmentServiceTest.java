package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.appointment.Appointment;
import com.zhiyu.health.entity.common.InAppMessage;
import com.zhiyu.health.entity.health.HealthProfile;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.mapper.appointment.AppointmentMapper;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.scheduling.ScheduleMapper;
import com.zhiyu.health.mapper.scheduling.ScheduleRequestMapper;
import com.zhiyu.health.service.appointment.AppointmentService;
import com.zhiyu.health.service.appointment.PaymentService;
import com.zhiyu.health.service.appointment.mapping.AppointmentDtoMapper;
import com.zhiyu.health.service.health.HealthProfileService;
import com.zhiyu.health.service.scheduling.SlotAccounting;
import com.zhiyu.health.service.scheduling.SlotWindowGuard;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import com.zhiyu.health.support.TestSlotWindows;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AppointmentServiceTest {

    private final AppointmentMapper appointmentMapper = mock(AppointmentMapper.class);
    private final ScheduleMapper scheduleMapper = mock(ScheduleMapper.class);
    private final ScheduleRequestMapper scheduleRequestMapper = mock(ScheduleRequestMapper.class);
    private final InAppMessageMapper messageMapper = mock(InAppMessageMapper.class);
    private final InMemorySlotCounter slotCounter = new InMemorySlotCounter();
    private final HealthProfileService healthProfiles = mock(HealthProfileService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final AppointmentDtoMapper appointmentDtos = Mappers.getMapper(AppointmentDtoMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    // 时段截止判断：默认固定到上午 10:00（上午未结束），现有用例不设 date/timeSlot 不受影响，
    // 设了上午时段的用例也不会被误判截止；已过时段用例用 12:00 的 guard 单独构造。
    private final SlotWindowGuard slotWindowGuard = new SlotWindowGuard(
            Clock.fixed(Instant.parse("2026-07-28T10:00:00+08:00"), ZoneId.of("Asia/Shanghai")),
            TestSlotWindows.contractOnly());

    @Test
    void createsAppointmentBeforeConditionSummaryGeneration() {
        java.util.concurrent.atomic.AtomicReference<Appointment> inserted =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 3));
        when(scheduleMapper.decrementRemainingSlots(9L)).thenReturn(1);
        when(appointmentMapper.nextSequenceNumber(9L)).thenReturn(1);
        when(appointmentMapper.insert(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setId(21L);
            inserted.set(appointment);
            return 1;
        });
        Appointment createdView = view("PENDING_PAYMENT", 1);
        createdView.setConditionSummary(null);
        when(appointmentMapper.selectViewById(21L)).thenReturn(createdView);
        slotCounter.initialize(9L, 3);

        AppointmentService.AppointmentView created = service().create(12L, 7L, 9L);

        // 挂号成功即进入待支付（票 81），支付完成才推进为待就诊。
        assertThat(created.status()).isEqualTo("待支付");
        assertThat(created.sequenceNumber()).isEqualTo(1);
        assertThat(created.conditionSummary()).isNull();
        assertThat(inserted.get().getConditionSummary()).isNull();
        assertThat(inserted.get().getHealthProfileId()).isEqualTo(31L);
        assertThat(inserted.get().getRegistrationFee()).isEqualByComparingTo("30.00");
        verify(payments).createUnpaid(21L, new BigDecimal("30.00"));
        assertThat(slotCounter.values.get(9L)).hasValue(2);
    }

    @Test
    void directAppointmentDeductsSlotAndCreatesPayment() {
        // 票 81 修订票 41 边界：直挂号与 AI 引导挂号统一走待支付，建收费记录。
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(1, 1));
        when(scheduleMapper.decrementRemainingSlots(9L)).thenReturn(1);
        when(appointmentMapper.nextSequenceNumber(9L)).thenReturn(1);
        when(appointmentMapper.insert(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setId(21L);
            return 1;
        });
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("PENDING_PAYMENT", 1));
        slotCounter.initialize(9L, 1);

        AppointmentService.AppointmentView result = service().createDirect(12L, 9L);

        assertThat(result.id()).isEqualTo(21L);
        assertThat(slotCounter.values.get(9L)).hasValue(0);
        verify(payments).createUnpaid(21L, new BigDecimal("30.00"));
    }

    @Test
    void directDuplicateReturnsConflictWithoutDeductingAgain() {
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 2));
        when(appointmentMapper.selectForProfileAndSchedule(12L, 31L, 9L, "CANCELLED"))
                .thenReturn(appointment(21L, "BOOKED"));
        slotCounter.initialize(9L, 2);

        assertThatThrownBy(() -> service().createDirect(12L, 9L))
                .isInstanceOf(ApiException.class)
                .hasMessage("请勿重复挂号");
        assertThat(slotCounter.values.get(9L)).hasValue(2);
        verify(scheduleMapper, never()).decrementRemainingSlots(9L);
        verify(payments, never()).createUnpaid(anyLong(), any());
    }

    @Test
    void savesGeneratedSummaryOnlyForOwningPatientAndConversation() {
        // 摘要纯内容直存，不再拼接免责文案；标注在响应装配时挂载。
        when(appointmentMapper.updateConditionSummary(21L, 12L, 31L, 7L, "主诉胸闷两天"))
                .thenReturn(1);
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("BOOKED", 1));

        AppointmentService.AppointmentView updated = service().saveConditionSummary(12L, 7L, 21L, "主诉胸闷两天");

        assertThat(updated.conditionSummary()).isEqualTo("主诉胸闷两天");
    }

    @Test
    void duplicateReturnsExistingAppointmentWithoutDeductingAgain() {
        Appointment existing = appointment(21L, "BOOKED");
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 2));
        when(appointmentMapper.selectForProfileAndSchedule(12L, 31L, 9L, "CANCELLED"))
                .thenReturn(existing);
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("BOOKED", 1));
        slotCounter.initialize(9L, 2);

        AppointmentService.AppointmentView result = service().create(12L, 7L, 9L);

        assertThat(result.id()).isEqualTo(21L);
        assertThat(slotCounter.values.get(9L)).hasValue(2);
        verify(scheduleMapper, never()).decrementRemainingSlots(9L);
    }

    @Test
    void creatingAppointmentWritesCareMessageInTransaction() throws Exception {
        // 票 43：挂号成功事务内写一条 appointment_care 关怀消息，disclaimer 经契约注入
        java.util.concurrent.atomic.AtomicReference<InAppMessage> savedMessage =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 3));
        when(scheduleMapper.decrementRemainingSlots(9L)).thenReturn(1);
        when(appointmentMapper.nextSequenceNumber(9L)).thenReturn(1);
        when(appointmentMapper.insert(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setId(21L);
            return 1;
        });
        when(messageMapper.insert(any(InAppMessage.class))).thenAnswer(invocation -> {
            savedMessage.set(invocation.getArgument(0));
            return 1;
        });
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("BOOKED", 1));
        slotCounter.initialize(9L, 3);

        service().create(12L, 7L, 9L);

        InAppMessage message = savedMessage.get();
        assertThat(message).as("挂号成功必须写入就诊指引卡关怀消息").isNotNull();
        assertThat(message.getType()).isEqualTo("appointment_care");
        assertThat(message.getTitle()).isEqualTo("就诊指引");
        assertThat(message.getPatientId()).isEqualTo(12L);
        assertThat(message.getRelatedAppointmentId()).isEqualTo(21L);
        assertThat(message.getDisclaimer()).isEqualTo("仅供参考，不替代医生诊断");
        // content 是结构化 JSON：含医院/科室/医生/地址/楼层/材料/注意事项
        com.fasterxml.jackson.databind.JsonNode content = objectMapper.readTree(message.getContent());
        assertThat(content.get("hospital_name").asText()).isEqualTo("郑州智愈综合医院");
        assertThat(content.get("department_name").asText()).isEqualTo("心血管内科");
        assertThat(content.get("doctor_name").asText()).isEqualTo("周安宁");
        assertThat(content.get("schedule_time").asText()).isEqualTo("2026-07-29 上午");
        assertThat(content.get("address").asText()).isEqualTo("郑州市金水区健康路 88 号");
        assertThat(content.get("materials").isArray()).isTrue();
        assertThat(content.get("materials").size()).isEqualTo(2);
        assertThat(content.get("precautions").isArray()).isTrue();
        assertThat(content.get("precautions").size()).isEqualTo(2);
    }

    @Test
    void duplicateAppointmentDoesNotWriteCareMessage() {
        // 票 43 幂等：重复挂号走 RETURN_EXISTING 早返回分支，不触达关怀消息写入
        Appointment existing = appointment(21L, "BOOKED");
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 2));
        when(appointmentMapper.selectForProfileAndSchedule(12L, 31L, 9L, "CANCELLED"))
                .thenReturn(existing);
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("BOOKED", 1));
        slotCounter.initialize(9L, 2);

        service().create(12L, 7L, 9L);

        verify(messageMapper, never()).insert(any(InAppMessage.class));
    }

    @Test
    void directDuplicateRejectsBeforeWritingCareMessage() {
        // 票 43 幂等：B 端直接挂号重复走 REJECT 抛 409，不触达关怀消息写入
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 2));
        when(appointmentMapper.selectForProfileAndSchedule(12L, 31L, 9L, "CANCELLED"))
                .thenReturn(appointment(21L, "BOOKED"));
        slotCounter.initialize(9L, 2);

        assertThatThrownBy(() -> service().createDirect(12L, 9L))
                .isInstanceOf(ApiException.class)
                .hasMessage("请勿重复挂号");
        verify(messageMapper, never()).insert(any(InAppMessage.class));
    }

    @Test
    void duplicateWithSummaryReturnsExistingResultWithoutRewritingFromNewConversation() {
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 2));
        when(appointmentMapper.selectForProfileAndSchedule(12L, 31L, 9L, "CANCELLED"))
                .thenReturn(appointment(21L, "BOOKED"));
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("BOOKED", 1));

        AppointmentService.AppointmentView result = service().createWithSummary(12L, 99L, 9L, "新会话摘要");

        assertThat(result.conditionSummary()).isEqualTo("主诉胸闷两天");
        verify(appointmentMapper, never())
                .updateConditionSummary(anyLong(), anyLong(), anyLong(), anyLong(), any(String.class));
    }

    @Test
    void summaryFailureKeepsCommittedAppointmentResult() {
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(1, 1));
        when(scheduleMapper.decrementRemainingSlots(9L)).thenReturn(1);
        when(appointmentMapper.nextSequenceNumber(9L)).thenReturn(1);
        when(appointmentMapper.insert(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setId(21L);
            return 1;
        });
        Appointment withoutSummary = view("BOOKED", 1);
        withoutSummary.setConditionSummary(null);
        when(appointmentMapper.selectViewById(21L)).thenReturn(withoutSummary);
        when(appointmentMapper.updateConditionSummary(anyLong(), anyLong(), anyLong(), anyLong(), any(String.class)))
                .thenThrow(new IllegalStateException("摘要存储失败"));
        slotCounter.initialize(9L, 1);

        AppointmentService.AppointmentView result = service().createWithSummary(12L, 7L, 9L, "主诉胸闷两天");

        assertThat(result.id()).isEqualTo(21L);
        assertThat(result.conditionSummary()).isNull();
        assertThat(slotCounter.values.get(9L)).hasValue(0);
    }

    @Test
    void paymentFailureKeepsCommittedAppointmentResult() {
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(1, 1));
        when(scheduleMapper.decrementRemainingSlots(9L)).thenReturn(1);
        when(appointmentMapper.nextSequenceNumber(9L)).thenReturn(1);
        when(appointmentMapper.insert(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setId(21L);
            return 1;
        });
        Appointment committed = view("BOOKED", 1);
        committed.setPaymentStatus(null);
        when(appointmentMapper.selectViewById(21L)).thenReturn(committed);
        doThrow(new IllegalStateException("收费记录写入失败")).when(payments).createUnpaid(21L, new BigDecimal("30.00"));
        slotCounter.initialize(9L, 1);

        AppointmentService.AppointmentView result = service().create(12L, 7L, 9L);

        assertThat(result.id()).isEqualTo(21L);
        assertThat(result.registrationFee()).isEqualByComparingTo("30.00");
        assertThat(result.paymentStatus()).isNull();
        assertThat(slotCounter.values.get(9L)).hasValue(0);
    }

    @Test
    void databaseCommitFailureRefundsRedisDeduction() {
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(1, 1));
        when(scheduleMapper.decrementRemainingSlots(9L)).thenReturn(1);
        when(scheduleMapper.selectCareContextBySchedule(9L)).thenReturn(careContext());
        when(scheduleRequestMapper.countPendingBlockingBySchedule(9L)).thenReturn(0);
        when(appointmentMapper.nextSequenceNumber(9L)).thenReturn(1);
        when(appointmentMapper.insert(any(Appointment.class))).thenAnswer(invocation -> {
            invocation.<Appointment>getArgument(0).setId(21L);
            return 1;
        });
        slotCounter.initialize(9L, 1);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            callback.doInTransaction(mock(TransactionStatus.class));
            throw new IllegalStateException("模拟提交失败");
        });

        AppointmentService service = new AppointmentService(
                appointmentMapper,
                scheduleMapper,
                scheduleRequestMapper,
                messageMapper,
                new SlotAccounting(slotCounter),
                transaction,
                activeProfileService(),
                payments,
                TestContracts.instance(),
                appointmentDtos,
                TestDisclaimers.instance(),
                objectMapper,
                slotWindowGuard);

        assertThatThrownBy(() -> service.create(12L, 7L, 9L)).isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values.get(9L)).hasValue(1);
    }

    @Test
    void repeatedCancellationRefundsOnlyOnce() {
        Appointment booked = appointment(21L, "BOOKED");
        Appointment cancelled = appointment(21L, "CANCELLED");
        when(appointmentMapper.selectByIdForUpdate(21L, 12L, 31L)).thenReturn(booked, cancelled);
        // BOOKED 取消需查 schedule 判断时间窗口：排班日期为明天（非当天），未过取消截止。
        when(scheduleMapper.selectById(9L)).thenReturn(futureSchedule());
        // cancel.from = [PENDING_PAYMENT, BOOKED]（票 81）：markCancelled 接收两个来源态。
        when(appointmentMapper.markCancelled(21L, "PENDING_PAYMENT", "BOOKED", "CANCELLED"))
                .thenReturn(1);
        when(scheduleMapper.incrementRemainingSlots(9L)).thenReturn(1);
        // 票 90：BOOKED 取消触发退款（PAID->REFUNDED）；重复取消时已是 CANCELLED，在幂等早返回分支
        // 不再触达 refundIfPaid，CAS 守卫保证退款只发生一次。
        when(payments.refundIfPaid(21L)).thenReturn(true);
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("CANCELLED", 1));
        slotCounter.initialize(9L, 2);
        AppointmentService service = service();

        service.cancel(12L, 21L);
        service.cancel(12L, 21L);

        assertThat(slotCounter.values.get(9L)).hasValue(3);
        verify(appointmentMapper).markCancelled(21L, "PENDING_PAYMENT", "BOOKED", "CANCELLED");
        verify(scheduleMapper).incrementRemainingSlots(9L);
        verify(payments, times(1)).refundIfPaid(21L);
    }

    @Test
    void bookedAppointmentCancelledBeforeCutoffRefundsAndReleasesSlot() {
        // 票 90：已支付预约在号源起始时间半小时前可取消，同事务退款（PAID->REFUNDED）并释放号源。
        Appointment booked = appointment(21L, "BOOKED");
        when(appointmentMapper.selectByIdForUpdate(21L, 12L, 31L)).thenReturn(booked);
        // 排班日期为明天（非当天），未过取消截止，可取消。
        when(scheduleMapper.selectById(9L)).thenReturn(futureSchedule());
        when(appointmentMapper.markCancelled(21L, "PENDING_PAYMENT", "BOOKED", "CANCELLED"))
                .thenReturn(1);
        when(scheduleMapper.incrementRemainingSlots(9L)).thenReturn(1);
        when(payments.refundIfPaid(21L)).thenReturn(true);
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("CANCELLED", 1));
        slotCounter.initialize(9L, 2);

        service().cancel(12L, 21L);

        verify(appointmentMapper).markCancelled(21L, "PENDING_PAYMENT", "BOOKED", "CANCELLED");
        verify(scheduleMapper).incrementRemainingSlots(9L);
        verify(payments).refundIfPaid(21L);
        assertThat(slotCounter.values.get(9L)).hasValue(3);
    }

    @Test
    void bookedAppointmentPastCancelCutoffIsRejected() {
        // 票 90：已支付预约距号源起始时间不足半小时不可取消。
        // 时钟固定 08:45（上午窗口 09:00，截止 = 09:00 - 30min = 08:30，当前 08:45 已过截止）。
        Appointment booked = appointment(21L, "BOOKED");
        booked.setScheduleDate(java.time.LocalDate.of(2026, 7, 28));
        booked.setTimeSlot(com.zhiyu.health.entity.scheduling.TimeSlot.MORNING);
        when(appointmentMapper.selectByIdForUpdate(21L, 12L, 31L)).thenReturn(booked);
        when(scheduleMapper.selectById(9L)).thenReturn(todayMorningSchedule());
        SlotWindowGuard pastCutoffGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-07-28T08:45:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());

        assertThatThrownBy(() -> serviceWithGuard(pastCutoffGuard).cancel(12L, 21L))
                .isInstanceOf(ApiException.class)
                .hasMessage("距就诊开始不足半小时，不可取消");

        verify(appointmentMapper, never()).markCancelled(anyLong(), any(), any(), any());
        verify(scheduleMapper, never()).incrementRemainingSlots(anyLong());
        verify(payments, never()).refundIfPaid(anyLong());
    }

    @Test
    void bookedAppointmentBeforeCutoffIsCancellable() {
        // 票 90：已支付预约在号源起始时间半小时前（当前 08:25 < 截止 08:30）可取消。
        Appointment booked = appointment(21L, "BOOKED");
        booked.setScheduleDate(java.time.LocalDate.of(2026, 7, 28));
        booked.setTimeSlot(com.zhiyu.health.entity.scheduling.TimeSlot.MORNING);
        when(appointmentMapper.selectByIdForUpdate(21L, 12L, 31L)).thenReturn(booked);
        when(scheduleMapper.selectById(9L)).thenReturn(todayMorningSchedule());
        when(appointmentMapper.markCancelled(21L, "PENDING_PAYMENT", "BOOKED", "CANCELLED"))
                .thenReturn(1);
        when(scheduleMapper.incrementRemainingSlots(9L)).thenReturn(1);
        when(payments.refundIfPaid(21L)).thenReturn(true);
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("CANCELLED", 1));
        slotCounter.initialize(9L, 2);
        SlotWindowGuard beforeCutoffGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-07-28T08:25:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());

        serviceWithGuard(beforeCutoffGuard).cancel(12L, 21L);

        verify(payments).refundIfPaid(21L);
        assertThat(slotCounter.values.get(9L)).hasValue(3);
    }

    @Test
    void pendingPaymentCancellationDoesNotRefund() {
        // 票 90：待支付取消不触发退款（payment 仍 UNPAID），只释放号源。
        Appointment pending = appointment(21L, "PENDING_PAYMENT");
        when(appointmentMapper.selectByIdForUpdate(21L, 12L, 31L)).thenReturn(pending);
        when(appointmentMapper.markCancelled(21L, "PENDING_PAYMENT", "BOOKED", "CANCELLED"))
                .thenReturn(1);
        when(scheduleMapper.incrementRemainingSlots(9L)).thenReturn(1);
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("CANCELLED", 1));
        slotCounter.initialize(9L, 2);

        service().cancel(12L, 21L);

        verify(payments, never()).refundIfPaid(anyLong());
        verify(scheduleMapper).incrementRemainingSlots(9L);
        assertThat(slotCounter.values.get(9L)).hasValue(3);
    }

    @Test
    void inProgressAppointmentCannotBeCancelled() {
        when(appointmentMapper.selectByIdForUpdate(21L, 12L, 31L)).thenReturn(appointment(21L, "IN_PROGRESS"));

        assertThatThrownBy(() -> service().cancel(12L, 21L))
                .isInstanceOf(ApiException.class)
                .hasMessage("当前状态不可取消");

        verify(appointmentMapper, never()).markCancelled(anyLong(), any(), any(), any());
        verify(scheduleMapper, never()).incrementRemainingSlots(anyLong());
    }

    @Test
    void overduePendingPaymentIsCancelledAndSlotRefunded() {
        // 票 81 支付超时惰性收敛：过期待支付单被推进为已取消并释放号源。
        slotCounter.initialize(9L, 2);
        AppointmentService service = service();
        // service() 内部 serviceWithGuard 已把 selectOverduePending(any()) 默认置空，
        // 此处在构造后再覆盖为非空，模拟存在过期待支付单。
        when(appointmentMapper.selectOverduePending("PENDING_PAYMENT"))
                .thenReturn(java.util.List.of(new AppointmentMapper.OverdueAppointment(21L, 9L)));
        when(appointmentMapper.markCancelled(21L, "PENDING_PAYMENT", "BOOKED", "CANCELLED"))
                .thenReturn(1);
        when(scheduleMapper.incrementRemainingSlots(9L)).thenReturn(1);

        service.expireOverdueAppointments();

        verify(appointmentMapper).markCancelled(21L, "PENDING_PAYMENT", "BOOKED", "CANCELLED");
        verify(scheduleMapper).incrementRemainingSlots(9L);
        // 号源已释放回补（withRefund 的 grant 做 Redis INCR）。
        assertThat(slotCounter.values.get(9L)).hasValue(3);
    }

    @Test
    void overdueCancellationSkipsAlreadyCancelledAppointment() {
        // CAS 守卫：并发收敛或患者已手动取消时 markCancelled 返回 0，跳过号源回补。
        slotCounter.initialize(9L, 2);
        AppointmentService service = service();
        when(appointmentMapper.selectOverduePending("PENDING_PAYMENT"))
                .thenReturn(java.util.List.of(new AppointmentMapper.OverdueAppointment(21L, 9L)));
        when(appointmentMapper.markCancelled(21L, "PENDING_PAYMENT", "BOOKED", "CANCELLED"))
                .thenReturn(0);

        service.expireOverdueAppointments();

        verify(scheduleMapper, never()).incrementRemainingSlots(anyLong());
        assertThat(slotCounter.values.get(9L)).hasValue(2);
    }

    @Test
    void uncalledBookedAppointmentIsCancelledRefundedAndNotified() {
        // 票 92：过点未叫号已支付预约惰性收敛为已取消+退款+站内消息。
        slotCounter.initialize(9L, 2);
        // 过点 guard：当天上午已过 11:30（Clock 固定 12:00），isClosed 返回 true。
        SlotWindowGuard pastEndGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-07-28T12:00:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());
        AppointmentService service = serviceWithGuard(pastEndGuard);
        when(appointmentMapper.selectUncalledBookedToday("BOOKED"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.UncalledAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 28), "上午")));
        when(appointmentMapper.markCancelledIfBooked(21L, "BOOKED", "CANCELLED"))
                .thenReturn(1);
        when(scheduleMapper.incrementRemainingSlots(9L)).thenReturn(1);
        when(payments.refundIfPaid(21L)).thenReturn(true);

        service.expireUncalledAppointments();

        verify(appointmentMapper).markCancelledIfBooked(21L, "BOOKED", "CANCELLED");
        verify(scheduleMapper).incrementRemainingSlots(9L);
        verify(payments).refundIfPaid(21L);
        // 号源回补（withRefund grant 做 Redis INCR）。
        assertThat(slotCounter.values.get(9L)).hasValue(3);
        // 站内消息幂等写入，文案为契约固定「医生暂未接诊，费用已原路返回」。
        ArgumentCaptor<InAppMessage> msgCaptor = ArgumentCaptor.forClass(InAppMessage.class);
        verify(messageMapper).insertIgnoreConflict(msgCaptor.capture());
        InAppMessage msg = msgCaptor.getValue();
        assertThat(msg.getType()).isEqualTo("appointment_auto_cancelled");
        assertThat(msg.getTitle()).isEqualTo("挂号已自动取消");
        assertThat(msg.getContent()).isEqualTo("医生暂未接诊，费用已原路返回");
        assertThat(msg.getPatientId()).isEqualTo(12L);
        assertThat(msg.getRelatedAppointmentId()).isEqualTo(21L);
        assertThat(msg.getDisclaimer()).isNotBlank();
    }

    @Test
    void uncalledCancellationSkipsAppointmentStillInWindow() {
        // 未过点（上午 10:00 在窗口内）不收敛：isClosed 返回 false。
        slotCounter.initialize(9L, 2);
        AppointmentService service = service(); // 默认 guard Clock 10:00
        when(appointmentMapper.selectUncalledBookedToday("BOOKED"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.UncalledAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 28), "上午")));

        service.expireUncalledAppointments();

        verify(appointmentMapper, never()).markCancelledIfBooked(anyLong(), any(), any());
        verify(scheduleMapper, never()).incrementRemainingSlots(anyLong());
        verify(payments, never()).refundIfPaid(anyLong());
        verify(messageMapper, never()).insertIgnoreConflict(any());
    }

    @Test
    void uncalledCancellationSkipsInProgressAppointment() {
        // CAS 守卫：状态不再是 BOOKED（已叫号 IN_PROGRESS）则 markCancelledIfBooked 返回 0 跳过，不退款不发消息。
        slotCounter.initialize(9L, 2);
        SlotWindowGuard pastEndGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-07-28T12:00:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());
        AppointmentService service = serviceWithGuard(pastEndGuard);
        when(appointmentMapper.selectUncalledBookedToday("BOOKED"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.UncalledAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 28), "上午")));
        when(appointmentMapper.markCancelledIfBooked(21L, "BOOKED", "CANCELLED"))
                .thenReturn(0);

        service.expireUncalledAppointments();

        verify(scheduleMapper, never()).incrementRemainingSlots(anyLong());
        verify(payments, never()).refundIfPaid(anyLong());
        verify(messageMapper, never()).insertIgnoreConflict(any());
        assertThat(slotCounter.values.get(9L)).hasValue(2);
    }

    @Test
    void uncalledCancellationSkipsUnknownTimeSlot() {
        // fail-open：契约未定义窗口的时段（"夜间"）不取消，与 isClosed/isPastCancelCutoff 同口径。
        slotCounter.initialize(9L, 2);
        SlotWindowGuard pastEndGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-07-28T12:00:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());
        AppointmentService service = serviceWithGuard(pastEndGuard);
        when(appointmentMapper.selectUncalledBookedToday("BOOKED"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.UncalledAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 28), "夜间")));

        service.expireUncalledAppointments();

        verify(appointmentMapper, never()).markCancelledIfBooked(anyLong(), any(), any());
        verify(scheduleMapper, never()).incrementRemainingSlots(anyLong());
        verify(payments, never()).refundIfPaid(anyLong());
    }

    @Test
    void repeatedUncalledCancellationRefundsOnlyOnce() {
        // 幂等：重复收敛只首次退款+消息+回补（CAS 守卫 + insertIgnoreConflict UNIQUE）。
        slotCounter.initialize(9L, 2);
        SlotWindowGuard pastEndGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-07-28T12:00:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());
        AppointmentService service = serviceWithGuard(pastEndGuard);
        when(appointmentMapper.selectUncalledBookedToday("BOOKED"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.UncalledAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 28), "上午")));
        when(appointmentMapper.markCancelledIfBooked(21L, "BOOKED", "CANCELLED"))
                .thenReturn(1) // 首次收敛成功
                .thenReturn(0); // 二次收敛 CAS 跳过（状态已 CANCELLED）
        when(scheduleMapper.incrementRemainingSlots(9L)).thenReturn(1);
        when(payments.refundIfPaid(21L)).thenReturn(true);

        service.expireUncalledAppointments();
        service.expireUncalledAppointments();

        verify(appointmentMapper, times(2)).markCancelledIfBooked(21L, "BOOKED", "CANCELLED");
        verify(scheduleMapper, times(1)).incrementRemainingSlots(9L);
        verify(payments, times(1)).refundIfPaid(21L);
        verify(messageMapper, times(1)).insertIgnoreConflict(any());
        assertThat(slotCounter.values.get(9L)).hasValue(3); // 只回补一次
    }

    @Test
    void overdueInProgressAppointmentIsAutoCompletedWithoutRecordOrMessage() {
        // 票 94：过点就诊中惰性收敛为已接诊，只推进状态，不落接诊记录、不发消息、不释放号源、不退款。
        SlotWindowGuard pastEndGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-07-28T12:00:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());
        AppointmentService service = serviceWithGuard(pastEndGuard);
        when(appointmentMapper.selectInProgress("IN_PROGRESS"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.InProgressAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 28), "上午")));
        when(appointmentMapper.markVisitedIfInProgress(21L, "IN_PROGRESS", "VISITED"))
                .thenReturn(1);

        service.expireUnfinishedConsultations();

        verify(appointmentMapper).markVisitedIfInProgress(21L, "IN_PROGRESS", "VISITED");
        // 不落接诊记录、不发消息、不释放号源、不退款。
        verify(messageMapper, never()).insert(any(InAppMessage.class));
        verify(messageMapper, never()).insertIgnoreConflict(any());
        verify(scheduleMapper, never()).incrementRemainingSlots(anyLong());
        verify(payments, never()).refundIfPaid(anyLong());
    }

    @Test
    void inProgressAppointmentWithinWindowNotAutoCompleted() {
        // 未过点（上午 10:00 在窗口内）不收敛：isPast 返回 false。
        AppointmentService service = service(); // 默认 guard Clock 10:00
        when(appointmentMapper.selectInProgress("IN_PROGRESS"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.InProgressAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 28), "上午")));

        service.expireUnfinishedConsultations();

        verify(appointmentMapper, never()).markVisitedIfInProgress(anyLong(), any(), any());
    }

    @Test
    void alreadyVisitedAppointmentSkippedByAutoComplete() {
        // CAS 守卫：状态不再是 IN_PROGRESS（医生已手动完成）则 markVisitedIfInProgress 返回 0 跳过。
        SlotWindowGuard pastEndGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-07-28T12:00:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());
        AppointmentService service = serviceWithGuard(pastEndGuard);
        when(appointmentMapper.selectInProgress("IN_PROGRESS"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.InProgressAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 28), "上午")));
        when(appointmentMapper.markVisitedIfInProgress(21L, "IN_PROGRESS", "VISITED"))
                .thenReturn(0);

        service.expireUnfinishedConsultations();

        verify(scheduleMapper, never()).incrementRemainingSlots(anyLong());
        verify(messageMapper, never()).insertIgnoreConflict(any());
    }

    @Test
    void crossDayInProgressAppointmentIsAutoCompleted() {
        // 跨天滞留（schedule_date = 昨天）必收敛：isPast 对 schedule_date < today 直接返回 true（不论时段）。
        AppointmentService service = service(); // 默认 guard Clock 2026-07-28T10:00
        when(appointmentMapper.selectInProgress("IN_PROGRESS"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.InProgressAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 27), "上午")));
        when(appointmentMapper.markVisitedIfInProgress(21L, "IN_PROGRESS", "VISITED"))
                .thenReturn(1);

        service.expireUnfinishedConsultations();

        verify(appointmentMapper).markVisitedIfInProgress(21L, "IN_PROGRESS", "VISITED");
    }

    @Test
    void inProgressUnknownTimeSlotTodayNotAutoCompleted() {
        // 当天未知时段 fail-open：isPast 当天复用 isClosed，未知时段返回 false，不收敛。
        SlotWindowGuard pastEndGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-07-28T12:00:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());
        AppointmentService service = serviceWithGuard(pastEndGuard);
        when(appointmentMapper.selectInProgress("IN_PROGRESS"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.InProgressAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 28), "夜间")));

        service.expireUnfinishedConsultations();

        verify(appointmentMapper, never()).markVisitedIfInProgress(anyLong(), any(), any());
    }

    @Test
    void repeatedAutoCompleteIsIdempotent() {
        // 幂等：重复收敛只首次推进（CAS 守卫），二次 markVisitedIfInProgress 返回 0 跳过。
        SlotWindowGuard pastEndGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-07-28T12:00:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());
        AppointmentService service = serviceWithGuard(pastEndGuard);
        when(appointmentMapper.selectInProgress("IN_PROGRESS"))
                .thenReturn(java.util.List.of(
                        new AppointmentMapper.InProgressAppointment(21L, 9L, 12L, LocalDate.of(2026, 7, 28), "上午")));
        when(appointmentMapper.markVisitedIfInProgress(21L, "IN_PROGRESS", "VISITED"))
                .thenReturn(1)
                .thenReturn(0);

        service.expireUnfinishedConsultations();
        service.expireUnfinishedConsultations();

        verify(appointmentMapper, times(2)).markVisitedIfInProgress(21L, "IN_PROGRESS", "VISITED");
    }

    @Test
    void soldOutAppointmentReturnsConflictWithoutTouchingPostgresCount() {
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(1, 0));
        slotCounter.initialize(9L, 0);

        assertThatThrownBy(() -> service().createDirect(12L, 9L))
                .isInstanceOf(ApiException.class)
                .hasMessage("号源已约满");
        assertThat(slotCounter.values.get(9L)).hasValue(0);
        verify(scheduleMapper, never()).decrementRemainingSlots(9L);
        verify(payments, never()).createUnpaid(anyLong(), any());
    }

    @Test
    void modifyPendingScheduleBlocksNewAppointmentWithoutDeducting() {
        // 调整号源待审核期间 C 端不可再预约：服务端确定性拦截，且不扣减 Redis/PG 号源。
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 3));
        slotCounter.initialize(9L, 3);

        AppointmentService service = service();
        when(scheduleRequestMapper.countPendingBlockingBySchedule(9L)).thenReturn(1);

        assertThatThrownBy(() -> service.createDirect(12L, 9L))
                .isInstanceOf(ApiException.class)
                .hasMessage("该排班正在调整号源或停诊审核中，暂不可挂号");
        assertThat(slotCounter.values.get(9L)).hasValue(3);
        verify(scheduleMapper, never()).decrementRemainingSlots(9L);
        verify(payments, never()).createUnpaid(anyLong(), any());
    }

    @Test
    void closedTimeWindowAppointmentReturnsConflictWithoutDeducting() {
        // 时段截止校验：当天上午已过 11:30（Clock 固定 12:00），有号源也不可挂号，且不扣减 Redis
        Schedule morningSchedule = schedule(3, 3);
        morningSchedule.setScheduleDate(LocalDate.of(2026, 8, 8));
        morningSchedule.setTimeSlot(com.zhiyu.health.entity.scheduling.TimeSlot.MORNING);
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(morningSchedule);
        slotCounter.initialize(9L, 3);
        SlotWindowGuard closedGuard = new SlotWindowGuard(
                Clock.fixed(Instant.parse("2026-08-08T12:00:00+08:00"), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());

        assertThatThrownBy(() -> serviceWithGuard(closedGuard).createDirect(12L, 9L))
                .isInstanceOf(ApiException.class)
                .hasMessage("该出诊时段已结束，不可再挂号");
        assertThat(slotCounter.values.get(9L)).hasValue(3);
        verify(scheduleMapper, never()).decrementRemainingSlots(9L);
        verify(payments, never()).createUnpaid(anyLong(), any());
    }

    private AppointmentService service() {
        return serviceWithGuard(slotWindowGuard);
    }

    private AppointmentService serviceWithGuard(SlotWindowGuard guard) {
        // 关怀消息上下文：默认提供一份联查结果，覆盖正常挂号路径
        when(scheduleMapper.selectCareContextBySchedule(9L)).thenReturn(careContext());
        // 停诊/调整号源审核冻结校验：默认无待审核申请，挂号不被冻结
        when(scheduleRequestMapper.countPendingBlockingBySchedule(9L)).thenReturn(0);
        // 支付超时惰性收敛（票 81）：默认无过期待支付单，list/cancel 入口收敛为空操作。
        when(appointmentMapper.selectOverduePending(any())).thenReturn(java.util.List.of());
        // 过点未叫号收敛（票 92）：默认无当天 BOOKED 单，list/cancel 入口收敛为空操作。
        when(appointmentMapper.selectUncalledBookedToday(any())).thenReturn(java.util.List.of());
        // 过点就诊中收敛（票 94）：默认无 IN_PROGRESS 单，list/cancel 入口收敛为空操作。
        when(appointmentMapper.selectInProgress(any())).thenReturn(java.util.List.of());
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        // autoCompleteOverdue 用 executeWithoutResult（无返回值事务），需单独 mock 执行回调。
        doAnswer(invocation -> {
                    java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transaction)
                .executeWithoutResult(any());
        return new AppointmentService(
                appointmentMapper,
                scheduleMapper,
                scheduleRequestMapper,
                messageMapper,
                new SlotAccounting(slotCounter),
                transaction,
                activeProfileService(),
                payments,
                TestContracts.instance(),
                appointmentDtos,
                TestDisclaimers.instance(),
                objectMapper,
                guard);
    }

    /** 票 49：CareContext 的地址/楼层/材料/注意事项由 SQL 联查院区（hospital_campuses）提供，此处桩值即院区静态值。 */
    private ScheduleMapper.CareContext careContext() {
        return new ScheduleMapper.CareContext(
                LocalDate.parse("2026-07-29"),
                "上午",
                "周安宁",
                "心血管内科",
                "郑州智愈综合医院",
                "郑州市金水区健康路 88 号",
                "门诊楼 1 层导诊台",
                "身份证或医保卡\n既往病历与检查报告",
                "建议提前 30 分钟到达并完成取号\n请携带既往病历便于医生参考");
    }

    private HealthProfileService activeProfileService() {
        HealthProfile profile = new HealthProfile();
        profile.setId(31L);
        when(healthProfiles.requireActive(anyLong())).thenReturn(profile);
        return healthProfiles;
    }

    private Schedule schedule(int total, int remaining) {
        Schedule schedule = new Schedule();
        schedule.setId(9L);
        schedule.setTotalSlots(total);
        schedule.setRemainingSlots(remaining);
        schedule.setIsActive(true);
        schedule.setRegistrationFee(new BigDecimal("30.00"));
        return schedule;
    }

    /** 未来日期排班（明天）：非当天，取消时间窗口不截止，BOOKED 可取消。 */
    private Schedule futureSchedule() {
        Schedule schedule = schedule(3, 2);
        schedule.setScheduleDate(LocalDate.of(2026, 7, 29));
        schedule.setTimeSlot(com.zhiyu.health.entity.scheduling.TimeSlot.MORNING);
        return schedule;
    }

    /** 当天上午排班：配合固定时钟判断取消截止（上午窗口 09:00，截止 = 08:30）。 */
    private Schedule todayMorningSchedule() {
        Schedule schedule = schedule(3, 2);
        schedule.setScheduleDate(LocalDate.of(2026, 7, 28));
        schedule.setTimeSlot(com.zhiyu.health.entity.scheduling.TimeSlot.MORNING);
        return schedule;
    }

    private Appointment appointment(long id, String status) {
        Appointment appointment = new Appointment();
        appointment.setId(id);
        appointment.setPatientId(12L);
        appointment.setConversationId(7L);
        appointment.setScheduleId(9L);
        appointment.setSequenceNumber(1);
        appointment.setStatus(status);
        return appointment;
    }

    private Appointment view(String status, int sequence) {
        Appointment appointment = appointment(21L, status);
        appointment.setDoctorId(2L);
        appointment.setDoctorName("周安宁");
        appointment.setDepartmentName("心血管内科");
        appointment.setScheduleDate(java.time.LocalDate.parse("2026-07-29"));
        appointment.setTimeSlot(com.zhiyu.health.entity.scheduling.TimeSlot.MORNING);
        appointment.setSequenceNumber(sequence);
        appointment.setRegistrationFee(new BigDecimal("30.00"));
        appointment.setPaymentStatus("UNPAID");
        appointment.setConditionSummary("主诉胸闷两天");
        appointment.setCreatedAt(java.time.OffsetDateTime.parse("2026-07-28T10:00:00+08:00"));
        return appointment;
    }
}

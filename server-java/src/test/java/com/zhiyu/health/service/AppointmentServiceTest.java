package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.InAppMessage;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.AppointmentMapper;
import com.zhiyu.health.mapper.InAppMessageMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import com.zhiyu.health.mapper.ScheduleRequestMapper;
import com.zhiyu.health.service.mapping.AppointmentDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
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
        Appointment createdView = view("BOOKED", 1);
        createdView.setConditionSummary(null);
        when(appointmentMapper.selectViewById(21L)).thenReturn(createdView);
        slotCounter.initialize(9L, 3);

        AppointmentService.AppointmentView created = service().create(12L, 7L, 9L);

        assertThat(created.status()).isEqualTo("已约");
        assertThat(created.sequenceNumber()).isEqualTo(1);
        assertThat(created.conditionSummary()).isNull();
        assertThat(inserted.get().getConditionSummary()).isNull();
        assertThat(inserted.get().getHealthProfileId()).isEqualTo(31L);
        assertThat(inserted.get().getRegistrationFee()).isEqualByComparingTo("30.00");
        verify(payments).createUnpaid(21L, new BigDecimal("30.00"));
        assertThat(slotCounter.values.get(9L)).hasValue(2);
    }

    @Test
    void directAppointmentDeductsSlotWithoutCreatingPayment() {
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(1, 1));
        when(scheduleMapper.decrementRemainingSlots(9L)).thenReturn(1);
        when(appointmentMapper.nextSequenceNumber(9L)).thenReturn(1);
        when(appointmentMapper.insert(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setId(21L);
            return 1;
        });
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("BOOKED", 1));
        slotCounter.initialize(9L, 1);

        AppointmentService.AppointmentView result = service().createDirect(12L, 9L);

        assertThat(result.id()).isEqualTo(21L);
        assertThat(slotCounter.values.get(9L)).hasValue(0);
        verify(payments, never()).createUnpaid(anyLong(), any());
    }

    @Test
    void directDuplicateReturnsConflictWithoutDeductingAgain() {
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 2));
        when(appointmentMapper.selectForProfileAndSchedule(12L, 31L, 9L)).thenReturn(appointment(21L, "BOOKED"));
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
        when(appointmentMapper.selectForProfileAndSchedule(12L, 31L, 9L)).thenReturn(existing);
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
        when(appointmentMapper.selectForProfileAndSchedule(12L, 31L, 9L)).thenReturn(existing);
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("BOOKED", 1));
        slotCounter.initialize(9L, 2);

        service().create(12L, 7L, 9L);

        verify(messageMapper, never()).insert(any(InAppMessage.class));
    }

    @Test
    void directDuplicateRejectsBeforeWritingCareMessage() {
        // 票 43 幂等：B 端直接挂号重复走 REJECT 抛 409，不触达关怀消息写入
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 2));
        when(appointmentMapper.selectForProfileAndSchedule(12L, 31L, 9L)).thenReturn(appointment(21L, "BOOKED"));
        slotCounter.initialize(9L, 2);

        assertThatThrownBy(() -> service().createDirect(12L, 9L))
                .isInstanceOf(ApiException.class)
                .hasMessage("请勿重复挂号");
        verify(messageMapper, never()).insert(any(InAppMessage.class));
    }

    @Test
    void duplicateWithSummaryReturnsExistingResultWithoutRewritingFromNewConversation() {
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 2));
        when(appointmentMapper.selectForProfileAndSchedule(12L, 31L, 9L)).thenReturn(appointment(21L, "BOOKED"));
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
        when(scheduleRequestMapper.countPendingDisableBySchedule(9L)).thenReturn(0);
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
                objectMapper);

        assertThatThrownBy(() -> service.create(12L, 7L, 9L)).isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values.get(9L)).hasValue(1);
    }

    @Test
    void repeatedCancellationRefundsOnlyOnce() {
        Appointment booked = appointment(21L, "BOOKED");
        Appointment cancelled = appointment(21L, "CANCELLED");
        when(appointmentMapper.selectByIdForUpdate(21L, 12L, 31L)).thenReturn(booked, cancelled);
        when(appointmentMapper.markCancelled(21L)).thenReturn(1);
        when(scheduleMapper.incrementRemainingSlots(9L)).thenReturn(1);
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("CANCELLED", 1));
        slotCounter.initialize(9L, 2);
        AppointmentService service = service();

        service.cancel(12L, 21L);
        service.cancel(12L, 21L);

        assertThat(slotCounter.values.get(9L)).hasValue(3);
        verify(appointmentMapper).markCancelled(21L);
        verify(scheduleMapper).incrementRemainingSlots(9L);
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

    private AppointmentService service() {
        // 关怀消息上下文：默认提供一份联查结果，覆盖正常挂号路径
        when(scheduleMapper.selectCareContextBySchedule(9L)).thenReturn(careContext());
        // 停诊审核冻结校验：默认无待审核停诊申请，挂号不被冻结
        when(scheduleRequestMapper.countPendingDisableBySchedule(9L)).thenReturn(0);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
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
                objectMapper);
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
        appointment.setTimeSlot(com.zhiyu.health.entity.TimeSlot.MORNING);
        appointment.setSequenceNumber(sequence);
        appointment.setRegistrationFee(new BigDecimal("30.00"));
        appointment.setPaymentStatus("UNPAID");
        appointment.setConditionSummary("主诉胸闷两天");
        appointment.setCreatedAt(java.time.OffsetDateTime.parse("2026-07-28T10:00:00+08:00"));
        return appointment;
    }
}

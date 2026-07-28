package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.AppointmentMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppointmentServiceTest {

    private final AppointmentMapper appointmentMapper = mock(AppointmentMapper.class);
    private final ScheduleMapper scheduleMapper = mock(ScheduleMapper.class);
    private final InMemorySlotCounter slotCounter = new InMemorySlotCounter();

    @Test
    void createsAppointmentWithSequenceAndSummaryAfterAtomicSlotDeduction() {
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
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("BOOKED", 1));
        slotCounter.initialize(9L, 3);

        AppointmentService.AppointmentView created = service().create(
                12L, 7L, 9L, "主诉胸闷两天，活动后加重");

        assertThat(created.status()).isEqualTo("已约");
        assertThat(created.sequenceNumber()).isEqualTo(1);
        assertThat(created.conditionSummary()).contains("仅供参考，不替代医生诊断");
        assertThat(inserted.get().getConditionSummary()).isEqualTo(
                "主诉胸闷两天，活动后加重。仅供参考，不替代医生诊断");
        assertThat(slotCounter.values.get(9L)).hasValue(2);
    }

    @Test
    void duplicateReturnsExistingAppointmentWithoutDeductingAgain() {
        Appointment existing = appointment(21L, "BOOKED");
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(3, 2));
        when(appointmentMapper.selectForPatientAndSchedule(12L, 9L)).thenReturn(existing);
        when(appointmentMapper.selectViewById(21L)).thenReturn(view("BOOKED", 1));
        slotCounter.initialize(9L, 2);

        AppointmentService.AppointmentView result = service().create(12L, 7L, 9L, "摘要");

        assertThat(result.id()).isEqualTo(21L);
        assertThat(slotCounter.values.get(9L)).hasValue(2);
        verify(scheduleMapper, never()).decrementRemainingSlots(9L);
    }

    @Test
    void databaseCommitFailureRefundsRedisDeduction() {
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(1, 1));
        when(scheduleMapper.decrementRemainingSlots(9L)).thenReturn(1);
        when(appointmentMapper.nextSequenceNumber(9L)).thenReturn(1);
        when(appointmentMapper.insert(any(Appointment.class))).thenReturn(1);
        slotCounter.initialize(9L, 1);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            callback.doInTransaction(mock(TransactionStatus.class));
            throw new IllegalStateException("模拟提交失败");
        });

        AppointmentService service = new AppointmentService(
                appointmentMapper, scheduleMapper, slotCounter, transaction);

        assertThatThrownBy(() -> service.create(12L, 7L, 9L, "摘要"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values.get(9L)).hasValue(1);
    }

    @Test
    void repeatedCancellationRefundsOnlyOnce() {
        Appointment booked = appointment(21L, "BOOKED");
        Appointment cancelled = appointment(21L, "CANCELLED");
        when(appointmentMapper.selectByIdForUpdate(21L, 12L)).thenReturn(booked, cancelled);
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

        assertThatThrownBy(() -> service().create(12L, 7L, 9L, "摘要"))
                .isInstanceOf(ApiException.class)
                .hasMessage("号源已约满");
        assertThat(slotCounter.values.get(9L)).hasValue(0);
        verify(scheduleMapper, never()).decrementRemainingSlots(9L);
    }

    private AppointmentService service() {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return new AppointmentService(appointmentMapper, scheduleMapper, slotCounter, transaction);
    }

    private Schedule schedule(int total, int remaining) {
        Schedule schedule = new Schedule();
        schedule.setId(9L);
        schedule.setTotalSlots(total);
        schedule.setRemainingSlots(remaining);
        schedule.setIsActive(true);
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
        appointment.setConditionSummary("主诉胸闷两天。仅供参考，不替代医生诊断");
        appointment.setCreatedAt(java.time.OffsetDateTime.parse("2026-07-28T10:00:00+08:00"));
        return appointment;
    }
}

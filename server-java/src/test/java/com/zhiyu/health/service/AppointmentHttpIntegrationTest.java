package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.controller.agent.AppointmentToolController;
import com.zhiyu.health.controller.mapping.AppointmentCardMapper;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.InAppMessage;
import com.zhiyu.health.entity.Payment;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.mapper.AppointmentMapper;
import com.zhiyu.health.mapper.InAppMessageMapper;
import com.zhiyu.health.mapper.PaymentMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import com.zhiyu.health.mapper.ScheduleRequestMapper;
import com.zhiyu.health.service.mapping.AppointmentDtoMapper;
import com.zhiyu.health.service.mapping.PaymentDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AppointmentHttpIntegrationTest {

    @Test
    void creatingAppointmentPersistsUnpaidFeeAndReturnsPrice() throws Exception {
        AppointmentMapper appointments = mock(AppointmentMapper.class);
        ScheduleMapper schedules = mock(ScheduleMapper.class);
        ScheduleRequestMapper scheduleRequests = mock(ScheduleRequestMapper.class);
        InAppMessageMapper messages = mock(InAppMessageMapper.class);
        PaymentMapper paymentMapper = mock(PaymentMapper.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        AtomicReference<Appointment> savedAppointment = new AtomicReference<>();
        AtomicReference<Payment> savedPayment = new AtomicReference<>();
        AtomicReference<InAppMessage> savedMessage = new AtomicReference<>();

        when(schedules.selectByIdForUpdate(9L)).thenReturn(schedule());
        when(schedules.decrementRemainingSlots(9L)).thenReturn(1);
        when(schedules.selectCareContextBySchedule(9L)).thenReturn(careContext());
        when(scheduleRequests.countPendingDisableBySchedule(9L)).thenReturn(0);
        when(appointments.nextSequenceNumber(9L)).thenReturn(1);
        when(appointments.insert(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setId(21L);
            savedAppointment.set(appointment);
            return 1;
        });
        when(messages.insert(any(InAppMessage.class))).thenAnswer(invocation -> {
            savedMessage.set(invocation.getArgument(0));
            return 1;
        });
        when(paymentMapper.insertUnpaid(any(Payment.class))).thenAnswer(invocation -> {
            savedPayment.set(invocation.getArgument(0));
            return 1;
        });
        when(appointments.selectViewById(21L))
                .thenAnswer(invocation -> view(savedAppointment.get(), savedPayment.get()));
        HealthProfile profile = new HealthProfile();
        profile.setId(31L);
        when(healthProfiles.requireActive(anyLong())).thenReturn(profile);

        InMemorySlotCounter slots = new InMemorySlotCounter();
        slots.initialize(9L, 1);
        PaymentService payments = new PaymentService(
                paymentMapper,
                immediateTransaction(),
                TestContracts.instance(),
                Mappers.getMapper(PaymentDtoMapper.class));
        AppointmentService service = new AppointmentService(
                appointments,
                schedules,
                scheduleRequests,
                messages,
                new SlotAccounting(slots),
                immediateTransaction(),
                healthProfiles,
                payments,
                TestContracts.instance(),
                Mappers.getMapper(AppointmentDtoMapper.class),
                TestDisclaimers.instance(),
                new ObjectMapper(),
                new SlotWindowGuard(TestContracts.instance(), java.time.Clock.systemDefaultZone()));
        MockMvc mvc = standaloneSetup(new AppointmentToolController(
                        service, TestDisclaimers.instance(), Mappers.getMapper(AppointmentCardMapper.class)))
                .build();

        mvc.perform(
                        post("/api/agent/appointments")
                                .contentType("application/json")
                                .content(
                                        """
                                {"patient_id":12,"conversation_id":7,"schedule_id":9,
                                 "condition_summary":"主诉胸闷两天"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration_fee").value(30.00))
                .andExpect(jsonPath("$.payment_status").value("UNPAID"))
                .andExpect(jsonPath("$.payment_status_label").value("待支付"));

        assertThat(savedPayment.get().getAppointmentId()).isEqualTo(21L);
        assertThat(savedPayment.get().getAmount()).isEqualByComparingTo("30.00");
        assertThat(savedPayment.get().getStatus()).isEqualTo("UNPAID");
        assertThat(slots.values.get(9L)).hasValue(0);
        // 票 43：挂号成功同时写入就诊指引卡关怀消息，type 与 disclaimer 来自契约
        InAppMessage careMessage = savedMessage.get();
        assertThat(careMessage).as("挂号成功必须写入就诊指引卡关怀消息").isNotNull();
        assertThat(careMessage.getType()).isEqualTo("appointment_care");
        assertThat(careMessage.getTitle()).isEqualTo("就诊指引");
        assertThat(careMessage.getDisclaimer()).isEqualTo("仅供参考，不替代医生诊断");
        assertThat(careMessage.getRelatedAppointmentId()).isEqualTo(21L);
    }

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
                "建议提前 30 分钟到达并完成取号");
    }

    private TransactionTemplate immediateTransaction() {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return transaction;
    }

    private Schedule schedule() {
        Schedule schedule = new Schedule();
        schedule.setId(9L);
        schedule.setIsActive(true);
        schedule.setRegistrationFee(new BigDecimal("30.00"));
        return schedule;
    }

    private Appointment view(Appointment saved, Payment payment) {
        saved.setDoctorId(2L);
        saved.setDoctorName("周安宁");
        saved.setDepartmentName("心血管内科");
        saved.setScheduleDate(LocalDate.parse("2026-07-29"));
        saved.setTimeSlot(TimeSlot.MORNING);
        saved.setConditionSummary("主诉胸闷两天");
        saved.setCreatedAt(OffsetDateTime.parse("2026-07-28T10:00:00+08:00"));
        saved.setPaymentStatus(payment == null ? null : payment.getStatus());
        return saved;
    }
}

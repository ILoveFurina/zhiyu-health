package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.controller.patient.appointment.AppointmentController;
import com.zhiyu.health.controller.patient.appointment.mapping.AppointmentCardMapper;
import com.zhiyu.health.entity.appointment.Appointment;
import com.zhiyu.health.entity.common.InAppMessage;
import com.zhiyu.health.entity.health.HealthProfile;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.entity.scheduling.TimeSlot;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** C 端直接挂号 HTTP seam：真实串起 controller、service 与原子号源边界。 */
class DirectAppointmentHttpIntegrationTest {

    private final AppointmentMapper appointments = mock(AppointmentMapper.class);
    private final ScheduleMapper schedules = mock(ScheduleMapper.class);
    private final ScheduleRequestMapper scheduleRequests = mock(ScheduleRequestMapper.class);
    private final InAppMessageMapper messages = mock(InAppMessageMapper.class);
    private final HealthProfileService healthProfiles = mock(HealthProfileService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final InMemorySlotCounter slots = new InMemorySlotCounter();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        HealthProfile profile = new HealthProfile();
        profile.setId(31L);
        when(healthProfiles.requireActive(anyLong())).thenReturn(profile);
        // B 端直接挂号成功也会写就诊指引卡关怀消息（票 43 覆盖所有入口）
        when(schedules.selectCareContextBySchedule(9L)).thenReturn(careContext());
        // 停诊审核冻结校验：默认无待审核停诊申请，挂号不被冻结
        when(scheduleRequests.countPendingDisableBySchedule(9L)).thenReturn(0);
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
        mvc = standaloneSetup(new AppointmentController(
                        service, TestDisclaimers.instance(), Mappers.getMapper(AppointmentCardMapper.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void successDeductsOneSlotAndCreatesPayment() throws Exception {
        // 票 81 修订票 41 边界：直挂号与 AI 引导挂号统一走待支付，建收费记录。
        when(schedules.selectByIdForUpdate(9L)).thenReturn(schedule(1));
        when(schedules.decrementRemainingSlots(9L)).thenReturn(1);
        when(appointments.nextSequenceNumber(9L)).thenReturn(1);
        when(appointments.insert(any(Appointment.class))).thenAnswer(invocation -> {
            invocation.<Appointment>getArgument(0).setId(21L);
            return 1;
        });
        when(messages.insert(any(InAppMessage.class))).thenReturn(1);
        when(appointments.selectViewById(21L)).thenReturn(view());
        slots.initialize(9L, 1);

        mvc.perform(post("/api/c/appointments")
                        .contentType("application/json")
                        .content("{\"schedule_id\":9}")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointment_id").value(21));

        assertThat(slots.values.get(9L)).hasValue(0);
        verify(schedules).decrementRemainingSlots(9L);
        verify(payments).createUnpaid(21L, new BigDecimal("30.00"));
        // 票 43：B 端直接挂号成功也写就诊指引卡关怀消息，覆盖所有挂号入口
        verify(messages).insert(any(InAppMessage.class));
    }

    @Test
    void soldOutReturnsConflictWithoutTouchingPostgresCount() throws Exception {
        when(schedules.selectByIdForUpdate(9L)).thenReturn(schedule(0));
        slots.initialize(9L, 0);

        mvc.perform(post("/api/c/appointments")
                        .contentType("application/json")
                        .content("{\"schedule_id\":9}")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("号源已约满"));

        verify(schedules, never()).decrementRemainingSlots(9L);
    }

    @Test
    void duplicateReturnsConflictWithoutDeductingAgain() throws Exception {
        when(schedules.selectByIdForUpdate(9L)).thenReturn(schedule(2));
        Appointment existing = new Appointment();
        existing.setId(21L);
        when(appointments.selectForProfileAndSchedule(12L, 31L, 9L, "CANCELLED"))
                .thenReturn(existing);
        slots.initialize(9L, 2);

        mvc.perform(post("/api/c/appointments")
                        .contentType("application/json")
                        .content("{\"schedule_id\":9}")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请勿重复挂号"));

        assertThat(slots.values.get(9L)).hasValue(2);
        verify(schedules, never()).decrementRemainingSlots(9L);
    }

    private ScheduleMapper.CareContext careContext() {
        return new ScheduleMapper.CareContext(
                LocalDate.parse("2026-08-03"),
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

    private Schedule schedule(int remaining) {
        Schedule schedule = new Schedule();
        schedule.setId(9L);
        schedule.setIsActive(true);
        schedule.setRemainingSlots(remaining);
        schedule.setRegistrationFee(new BigDecimal("30.00"));
        return schedule;
    }

    private Appointment view() {
        Appointment appointment = new Appointment();
        appointment.setId(21L);
        appointment.setPatientId(12L);
        appointment.setHealthProfileId(31L);
        appointment.setScheduleId(9L);
        appointment.setDoctorId(2L);
        appointment.setDoctorName("周安宁");
        appointment.setDepartmentName("心血管内科");
        appointment.setScheduleDate(LocalDate.parse("2026-08-03"));
        appointment.setTimeSlot(TimeSlot.MORNING);
        appointment.setSequenceNumber(1);
        appointment.setStatus("PENDING_PAYMENT");
        appointment.setRegistrationFee(new BigDecimal("30.00"));
        appointment.setCreatedAt(OffsetDateTime.parse("2026-08-02T10:00:00+08:00"));
        return appointment;
    }
}

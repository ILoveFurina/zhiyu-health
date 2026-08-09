package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.appointment.Appointment;
import com.zhiyu.health.entity.common.InAppMessage;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.consultation.ConsultationRecord;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.entity.scheduling.TimeSlot;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.consultation.ConsultationRecordMapper;
import com.zhiyu.health.mapper.consultation.ReceptionMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.appointment.AppointmentService;
import com.zhiyu.health.service.consultation.ReceptionService;
import com.zhiyu.health.service.scheduling.EffectiveSlotWindows;
import com.zhiyu.health.service.scheduling.SlotWindowGuard;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ReceptionServiceTest {

    private final StaffUserMapper staffUserMapper = mock(StaffUserMapper.class);
    private final ReceptionMapper receptionMapper = mock(ReceptionMapper.class);
    private final ConsultationRecordMapper consultationMapper = mock(ConsultationRecordMapper.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final AgentClient agentClient = mock(AgentClient.class);
    private final InAppMessageMapper messageMapper = mock(InAppMessageMapper.class);
    private final PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
    private final AppointmentService appointments = mock(AppointmentService.class);
    private ReceptionService service;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);
    // 固定到上午 10:00（处于上午窗口内），叫号测试不依赖墙上时钟。
    private static final Clock MORNING_CLOCK =
            Clock.fixed(ZonedDateTime.of(TODAY, LocalTime.of(10, 0), ZONE).toInstant(), ZONE);

    private ReceptionService buildService(Clock clock) {
        EffectiveSlotWindows effectiveWindows = new EffectiveSlotWindows(
                TestContracts.instance(), mock(StringRedisTemplate.class), new ObjectMapper(), false);
        return new ReceptionService(
                staffUserMapper,
                receptionMapper,
                consultationMapper,
                messageMapper,
                prescriptionMapper,
                transactionTemplate,
                agentClient,
                TestDisclaimers.instance(),
                TestContracts.instance(),
                new ObjectMapper(),
                appointments,
                new SlotWindowGuard(clock, effectiveWindows));
    }

    @BeforeEach
    void setUp() {
        service = buildService(MORNING_CLOCK);
        doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    @Test
    void dashboardQueriesOnlyDoctorBoundToAuthenticatedStaff() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        when(receptionMapper.selectSchedules(7L, LocalDate.now())).thenReturn(List.of());
        // 接诊台可见白名单（票 81）：待支付不进队列，只查 BOOKED/IN_PROGRESS/VISITED。
        when(receptionMapper.selectAppointments(7L, LocalDate.now(), "BOOKED", "IN_PROGRESS", "VISITED"))
                .thenReturn(List.of(appointment("BOOKED")));

        ReceptionService.ReceptionDashboard dashboard = service.today(8L);

        assertEquals(1, dashboard.appointments().size());
        verify(appointments).expireOverdueAppointments();
        verify(receptionMapper).selectSchedules(7L, LocalDate.now());
        verify(receptionMapper).selectAppointments(7L, LocalDate.now(), "BOOKED", "IN_PROGRESS", "VISITED");
    }

    @Test
    void dashboardMarksSoldOutScheduleAsFull() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        Schedule schedule = new Schedule();
        schedule.setId(3L);
        schedule.setTimeSlot(TimeSlot.MORNING);
        schedule.setTotalSlots(5);
        schedule.setRemainingSlots(0);
        schedule.setIsActive(true);
        schedule.setScheduleDate(TODAY);
        when(receptionMapper.selectSchedules(7L, LocalDate.now())).thenReturn(List.of(schedule));
        when(receptionMapper.selectAppointments(7L, LocalDate.now(), "BOOKED", "IN_PROGRESS", "VISITED"))
                .thenReturn(List.of());

        ReceptionService.ReceptionDashboard dashboard = service.today(8L);

        assertEquals(1, dashboard.schedules().size());
        assertEquals("FULL", dashboard.schedules().get(0).status());
        // 上午排班 + 上午 10:00 时钟：处于窗口内，卡片不应置灰（票 87）。
        assertEquals(true, dashboard.schedules().get(0).inWindow());
    }

    @Test
    void dashboardMarksBookedCallableWhenWithinWindow() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        when(receptionMapper.selectSchedules(7L, LocalDate.now())).thenReturn(List.of());
        when(receptionMapper.selectAppointments(7L, LocalDate.now(), "BOOKED", "IN_PROGRESS", "VISITED"))
                .thenReturn(List.of(appointment("BOOKED")));

        ReceptionService.ReceptionDashboard dashboard = service.today(8L);

        assertEquals(true, dashboard.appointments().get(0).callable());
    }

    @Test
    void dashboardMarksBookedNonCallableOutsideWindow() {
        // 午休 12:00 不在上午窗口内：待就诊患者不可叫号，且上午排班卡片置灰（票 87）。
        ReceptionService lunchService = buildService(
                Clock.fixed(ZonedDateTime.of(TODAY, LocalTime.of(12, 0), ZONE).toInstant(), ZONE));
        Schedule morning = new Schedule();
        morning.setId(3L);
        morning.setTimeSlot(TimeSlot.MORNING);
        morning.setTotalSlots(5);
        morning.setRemainingSlots(3);
        morning.setIsActive(true);
        morning.setScheduleDate(TODAY);
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        when(receptionMapper.selectSchedules(7L, LocalDate.now())).thenReturn(List.of(morning));
        when(receptionMapper.selectAppointments(7L, LocalDate.now(), "BOOKED", "IN_PROGRESS", "VISITED"))
                .thenReturn(List.of(appointment("BOOKED")));

        ReceptionService.ReceptionDashboard dashboard = lunchService.today(8L);

        assertEquals(false, dashboard.appointments().get(0).callable());
        assertEquals(false, dashboard.schedules().get(0).inWindow());
    }

    @Test
    void anotherDoctorsAppointmentIsNotVisible() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(null);

        ApiException exception = assertThrows(ApiException.class, () -> service.detail(8L, 21L));

        assertEquals(404, exception.getStatus());
        verify(receptionMapper).selectAppointment(21L, 7L);
    }

    @Test
    void completionPersistsRecordBeforeInProgressAppointmentBecomesVisited() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        Appointment inProgress = appointment("IN_PROGRESS");
        inProgress.setPatientId(5L);
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(inProgress, appointment("VISITED"));
        when(receptionMapper.selectAppointmentForUpdate(21L, 7L)).thenReturn(inProgress);
        when(receptionMapper.markVisited(21L, "IN_PROGRESS", "VISITED")).thenReturn(1);
        when(agentClient.summarizeConsultation("上呼吸道感染", "按需复诊"))
                .thenReturn(new AgentClient.ClinicalResponse("本次诊断为上呼吸道感染，请按需复诊。", "模型错误文案"));
        ConsultationRecord saved = new ConsultationRecord();
        saved.setDiagnosis("上呼吸道感染");
        saved.setAdvice("按需复诊");
        saved.setCreatedAt(OffsetDateTime.now());
        when(consultationMapper.selectByAppointmentId(21L)).thenReturn(saved);

        ReceptionService.AppointmentDetail result = service.complete(8L, 21L, " 上呼吸道感染 ", " 按需复诊 ");

        ArgumentCaptor<ConsultationRecord> captor = ArgumentCaptor.forClass(ConsultationRecord.class);
        verify(consultationMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getDoctorId());
        assertEquals("上呼吸道感染", captor.getValue().getDiagnosis());
        verify(receptionMapper).markVisited(21L, "IN_PROGRESS", "VISITED");
        ArgumentCaptor<InAppMessage> messageCaptor = ArgumentCaptor.forClass(InAppMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals(5L, messageCaptor.getValue().getPatientId());
        assertEquals("本次诊断为上呼吸道感染，请按需复诊。", messageCaptor.getValue().getContent());
        assertEquals("仅供参考，不替代医生诊断", messageCaptor.getValue().getDisclaimer());
        verify(agentClient).summarizeConsultation("上呼吸道感染", "按需复诊");
        assertEquals("已接诊", result.appointment().status());
    }

    @Test
    void completionRejectsBookedAppointmentBeforeCallingAgent() {
        // 票 87：废弃 BOOKED -> VISITED 直通兜底，必须先叫号进入就诊中才能完成接诊。
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(appointment("BOOKED"));

        ApiException exception = assertThrows(ApiException.class, () -> service.complete(8L, 21L, "上呼吸道感染", "按需复诊"));

        assertEquals(409, exception.getStatus());
        assertEquals("当前状态不可接诊", exception.getMessage());
        verify(agentClient, never()).summarizeConsultation(any(), any());
        verify(receptionMapper, never()).markVisited(anyLong(), any(), any());
    }

    @Test
    void completionAlsoAcceptsInProgressAppointment() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        Appointment inProgress = appointment("IN_PROGRESS");
        inProgress.setPatientId(5L);
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(inProgress, appointment("VISITED"));
        when(receptionMapper.selectAppointmentForUpdate(21L, 7L)).thenReturn(inProgress);
        when(receptionMapper.markVisited(21L, "IN_PROGRESS", "VISITED")).thenReturn(1);
        when(agentClient.summarizeConsultation("上呼吸道感染", "按需复诊"))
                .thenReturn(new AgentClient.ClinicalResponse("请按需复诊。", "ignored"));

        ReceptionService.AppointmentDetail result = service.complete(8L, 21L, "上呼吸道感染", "按需复诊");

        assertEquals("已接诊", result.appointment().status());
        verify(receptionMapper).markVisited(21L, "IN_PROGRESS", "VISITED");
    }

    @Test
    void completionRejectsCancelledAppointmentBeforeCallingAgent() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(appointment("CANCELLED"));

        ApiException exception = assertThrows(ApiException.class, () -> service.complete(8L, 21L, "上呼吸道感染", "按需复诊"));

        assertEquals(409, exception.getStatus());
        verify(agentClient, never()).summarizeConsultation(any(), any());
    }

    @Test
    void calledAppointmentBecomesInProgressAndWritesOneStructuredNotice() throws Exception {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        Appointment booked = appointment("BOOKED");
        booked.setPatientId(5L);
        booked.setRoom("东区 301 室");
        Appointment inProgress = appointment("IN_PROGRESS");
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(booked, inProgress);
        when(receptionMapper.selectAppointmentForUpdate(21L, 7L)).thenReturn(booked);
        // 单叫号约束（票 81）：该医生当前无就诊中挂号，允许叫号。
        when(receptionMapper.selectInProgressForDoctor(7L, "IN_PROGRESS")).thenReturn(null);
        when(receptionMapper.markInProgress(21L, "BOOKED", "IN_PROGRESS")).thenReturn(1);

        ReceptionService.AppointmentDetail result = service.call(8L, 21L);

        verify(receptionMapper).markInProgress(21L, "BOOKED", "IN_PROGRESS");
        ArgumentCaptor<InAppMessage> captor = ArgumentCaptor.forClass(InAppMessage.class);
        verify(messageMapper).insert(captor.capture());
        InAppMessage notice = captor.getValue();
        assertEquals(5L, notice.getPatientId());
        assertEquals(21L, notice.getRelatedAppointmentId());
        assertEquals("appointment_called", notice.getType());
        assertEquals("请到诊室就诊", notice.getTitle());
        assertEquals("仅供参考，不替代医生诊断", notice.getDisclaimer());
        JsonNode content = new ObjectMapper().readTree(notice.getContent());
        assertEquals("东区 301 室", content.get("room").asText());
        assertEquals(2, content.get("sequence_number").asInt());
        assertEquals("上午", content.get("time_slot").asText());
        assertEquals("就诊中", result.appointment().status());
    }

    @Test
    void repeatedCallReturnsCurrentAppointmentWithoutAnotherNotice() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        Appointment inProgress = appointment("IN_PROGRESS");
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(inProgress, inProgress);

        ReceptionService.AppointmentDetail result = service.call(8L, 21L);

        assertEquals("就诊中", result.appointment().status());
        verify(receptionMapper, never()).selectAppointmentForUpdate(21L, 7L);
        verify(messageMapper, never()).insert(any(InAppMessage.class));
        verify(receptionMapper, never()).markInProgress(anyLong(), any(), any());
    }

    @Test
    void anotherDoctorsAppointmentCannotBeCalled() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(null);

        ApiException exception = assertThrows(ApiException.class, () -> service.call(8L, 21L));

        assertEquals(404, exception.getStatus());
        verify(messageMapper, never()).insert(any(InAppMessage.class));
    }

    @Test
    void cancelledAppointmentCannotBeCalled() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(appointment("CANCELLED"));

        ApiException exception = assertThrows(ApiException.class, () -> service.call(8L, 21L));

        assertEquals(409, exception.getStatus());
        verify(messageMapper, never()).insert(any(InAppMessage.class));
    }

    @Test
    void callRejectedWhenDoctorAlreadyHasInProgressAppointment() {
        // 单叫号约束（票 81，ADR-0033）：医生维度同时只能一条就诊中，
        // 既有 IN_PROGRESS 时必须先完成接诊才能叫下一个号。
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        Appointment booked = appointment("BOOKED");
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(booked, booked);
        when(receptionMapper.selectAppointmentForUpdate(21L, 7L)).thenReturn(booked);
        // 该医生已有一条就诊中挂号（另一患者），叫号被拒。
        when(receptionMapper.selectInProgressForDoctor(7L, "IN_PROGRESS")).thenReturn(99L);

        ApiException exception = assertThrows(ApiException.class, () -> service.call(8L, 21L));

        assertEquals(409, exception.getStatus());
        assertEquals("请先完成当前就诊后再叫下一个号", exception.getMessage());
        verify(messageMapper, never()).insert(any(InAppMessage.class));
        verify(receptionMapper, never()).markInProgress(anyLong(), any(), any());
    }

    @Test
    void callRejectedOutsideTimeSlotWindow() {
        // 午休 12:00 不在上午窗口内：过点滞留的待就诊患者不可叫号（票 87）。
        ReceptionService lunchService = buildService(
                Clock.fixed(ZonedDateTime.of(TODAY, LocalTime.of(12, 0), ZONE).toInstant(), ZONE));
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(appointment("BOOKED"));

        ApiException exception = assertThrows(ApiException.class, () -> lunchService.call(8L, 21L));

        assertEquals(409, exception.getStatus());
        assertEquals("当前不在该挂号所属出诊时段内，暂不可叫号", exception.getMessage());
        verify(messageMapper, never()).insert(any(InAppMessage.class));
        verify(receptionMapper, never()).markInProgress(anyLong(), any(), any());
    }

    private StaffUser doctorStaff(long doctorId) {
        StaffUser staff = new StaffUser();
        staff.setRole(StaffUser.ROLE_DOCTOR);
        staff.setDoctorId(doctorId);
        return staff;
    }

    private Appointment appointment(String status) {
        Appointment appointment = new Appointment();
        appointment.setId(21L);
        appointment.setScheduleId(3L);
        appointment.setPatientNickname("小愈");
        appointment.setSequenceNumber(2);
        appointment.setStatus(status);
        appointment.setScheduleDate(TODAY);
        appointment.setTimeSlot(TimeSlot.MORNING);
        appointment.setConditionSummary("咳嗽两天");
        return appointment;
    }
}

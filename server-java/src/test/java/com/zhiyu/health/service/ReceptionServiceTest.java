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
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.ConsultationRecord;
import com.zhiyu.health.entity.InAppMessage;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.mapper.ConsultationRecordMapper;
import com.zhiyu.health.mapper.InAppMessageMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.mapper.ReceptionMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    private ReceptionService service;

    @BeforeEach
    void setUp() {
        service = new ReceptionService(
                staffUserMapper,
                receptionMapper,
                consultationMapper,
                messageMapper,
                prescriptionMapper,
                transactionTemplate,
                agentClient,
                TestDisclaimers.instance(),
                TestContracts.instance(),
                new ObjectMapper());
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
        when(receptionMapper.selectAppointments(7L, LocalDate.now(), "CANCELLED"))
                .thenReturn(List.of(appointment("BOOKED")));

        ReceptionService.ReceptionDashboard dashboard = service.today(8L);

        assertEquals(1, dashboard.appointments().size());
        verify(receptionMapper).selectSchedules(7L, LocalDate.now());
        verify(receptionMapper).selectAppointments(7L, LocalDate.now(), "CANCELLED");
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
    void completionPersistsRecordBeforeBookedAppointmentBecomesVisited() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        Appointment booked = appointment("BOOKED");
        booked.setPatientId(5L);
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(booked, appointment("VISITED"));
        when(receptionMapper.selectAppointmentForUpdate(21L, 7L)).thenReturn(booked);
        when(receptionMapper.markVisited(21L, "BOOKED", "IN_PROGRESS", "VISITED"))
                .thenReturn(1);
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
        verify(receptionMapper).markVisited(21L, "BOOKED", "IN_PROGRESS", "VISITED");
        ArgumentCaptor<InAppMessage> messageCaptor = ArgumentCaptor.forClass(InAppMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals(5L, messageCaptor.getValue().getPatientId());
        assertEquals("本次诊断为上呼吸道感染，请按需复诊。", messageCaptor.getValue().getContent());
        assertEquals("仅供参考，不替代医生诊断", messageCaptor.getValue().getDisclaimer());
        verify(agentClient).summarizeConsultation("上呼吸道感染", "按需复诊");
        assertEquals("已接诊", result.appointment().status());
    }

    @Test
    void completionAlsoAcceptsInProgressAppointment() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctorStaff(7L));
        Appointment inProgress = appointment("IN_PROGRESS");
        inProgress.setPatientId(5L);
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(inProgress, appointment("VISITED"));
        when(receptionMapper.selectAppointmentForUpdate(21L, 7L)).thenReturn(inProgress);
        when(receptionMapper.markVisited(21L, "BOOKED", "IN_PROGRESS", "VISITED"))
                .thenReturn(1);
        when(agentClient.summarizeConsultation("上呼吸道感染", "按需复诊"))
                .thenReturn(new AgentClient.ClinicalResponse("请按需复诊。", "ignored"));

        ReceptionService.AppointmentDetail result = service.complete(8L, 21L, "上呼吸道感染", "按需复诊");

        assertEquals("已接诊", result.appointment().status());
        verify(receptionMapper).markVisited(21L, "BOOKED", "IN_PROGRESS", "VISITED");
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
        appointment.setScheduleDate(LocalDate.now());
        appointment.setTimeSlot(TimeSlot.MORNING);
        appointment.setConditionSummary("咳嗽两天");
        return appointment;
    }
}

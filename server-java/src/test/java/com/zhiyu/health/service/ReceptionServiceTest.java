package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.ConsultationRecord;
import com.zhiyu.health.entity.InAppMessage;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.mapper.ConsultationRecordMapper;
import com.zhiyu.health.mapper.InAppMessageMapper;
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
    private ReceptionService service;

    @BeforeEach
    void setUp() {
        service = new ReceptionService(
                staffUserMapper,
                receptionMapper,
                consultationMapper,
                messageMapper,
                transactionTemplate,
                agentClient,
                TestDisclaimers.instance(),
                TestContracts.instance());
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
        when(receptionMapper.selectAppointments(7L, LocalDate.now()))
                .thenReturn(List.of(appointment(Appointment.STATUS_BOOKED)));

        ReceptionService.ReceptionDashboard dashboard = service.today(8L);

        assertEquals(1, dashboard.appointments().size());
        verify(receptionMapper).selectSchedules(7L, LocalDate.now());
        verify(receptionMapper).selectAppointments(7L, LocalDate.now());
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
        Appointment booked = appointment(Appointment.STATUS_BOOKED);
        booked.setPatientId(5L);
        when(receptionMapper.selectAppointment(21L, 7L)).thenReturn(booked, appointment(Appointment.STATUS_VISITED));
        when(receptionMapper.selectAppointmentForUpdate(21L, 7L)).thenReturn(booked);
        when(receptionMapper.markVisited(21L)).thenReturn(1);
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
        verify(receptionMapper).markVisited(21L);
        ArgumentCaptor<InAppMessage> messageCaptor = ArgumentCaptor.forClass(InAppMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals(5L, messageCaptor.getValue().getPatientId());
        assertEquals("本次诊断为上呼吸道感染，请按需复诊。", messageCaptor.getValue().getContent());
        assertEquals("仅供参考，不替代医生诊断", messageCaptor.getValue().getDisclaimer());
        verify(agentClient).summarizeConsultation("上呼吸道感染", "按需复诊");
        assertEquals("已接诊", result.appointment().status());
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

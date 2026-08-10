package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.appointment.Appointment;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.mapper.consultation.ReceptionMapper;
import com.zhiyu.health.mapper.organization.DoctorMapper;
import com.zhiyu.health.service.consultation.ClinicalContextService;
import com.zhiyu.health.support.TestContracts;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** 统一临床上下文（票 56）：两种来源的身份派生、归属与状态守卫。 */
class ClinicalContextServiceTest {

    private final StaffUserMapper staffUserMapper = mock(StaffUserMapper.class);
    private final ReceptionMapper receptionMapper = mock(ReceptionMapper.class);
    private final OnlineConsultationMapper onlineConsultationMapper = mock(OnlineConsultationMapper.class);
    private final DoctorMapper doctorMapper = mock(DoctorMapper.class);
    private final ClinicalContextService service = new ClinicalContextService(
            staffUserMapper, receptionMapper, onlineConsultationMapper, doctorMapper, TestContracts.instance());

    // ------------------------------------------------------------------
    // 线下挂号来源
    // ------------------------------------------------------------------

    @Test
    void appointmentContextDerivesPatientProfileAndDoctor() {
        givenDoctor(8L, 5L);
        Appointment appointment = appointment();
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(appointment);

        ClinicalContextService.ClinicalContext context = service.requirePrescribableFromAppointment(8L, 21L);

        assertThat(context.patientId()).isEqualTo(12L);
        assertThat(context.healthProfileId()).isEqualTo(3L);
        assertThat(context.doctorId()).isEqualTo(5L);
        assertThat(context.sourceCampusId()).isEqualTo(7L);
        assertThat(context.sourceType()).isEqualTo("APPOINTMENT");
        assertThat(context.occurredAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-01T09:00:00+08:00").toLocalDateTime());
    }

    @Test
    void appointmentContextRejectsNonDoctorForeignAndCancelled() {
        StaffUser admin = new StaffUser();
        admin.setRole(StaffUser.ROLE_ADMIN);
        when(staffUserMapper.selectById(1L)).thenReturn(admin);
        assertThatThrownBy(() -> service.requirePrescribableFromAppointment(1L, 21L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(403);
                    assertThat(e.getMessage()).isEqualTo("仅医生可操作");
                });

        givenDoctor(8L, 5L);
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(null);
        assertThatThrownBy(() -> service.requirePrescribableFromAppointment(8L, 21L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(404);
                    assertThat(e.getMessage()).isEqualTo("挂号单不存在");
                });

        Appointment cancelled = appointment();
        cancelled.setStatus("CANCELLED");
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(cancelled);
        assertThatThrownBy(() -> service.requirePrescribableFromAppointment(8L, 21L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("已取消挂号不可开方");
                });
    }

    // ------------------------------------------------------------------
    // 在线问诊来源
    // ------------------------------------------------------------------

    @Test
    void onlineConsultationContextDerivesFromBoundInProgressConsultation() {
        givenDoctor(8L, 5L);
        when(onlineConsultationMapper.selectDetailedById(31L)).thenReturn(consultation("IN_PROGRESS", 5L));

        ClinicalContextService.ClinicalContext context = service.requirePrescribableFromOnlineConsultation(8L, 31L);

        assertThat(context.patientId()).isEqualTo(12L);
        assertThat(context.healthProfileId()).isEqualTo(3L);
        assertThat(context.doctorId()).isEqualTo(5L);
        assertThat(context.sourceCampusId()).isEqualTo(7L);
        assertThat(context.sourceType()).isEqualTo("ONLINE_CONSULTATION");
        // 发生时间取接诊时刻，不取创建或完成时间
        assertThat(context.occurredAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-01T10:03:00+08:00").toLocalDateTime());
    }

    @Test
    void onlineConsultationContextRejectsNonDoctor() {
        StaffUser admin = new StaffUser();
        admin.setRole(StaffUser.ROLE_ADMIN);
        when(staffUserMapper.selectById(1L)).thenReturn(admin);

        assertThatThrownBy(() -> service.requirePrescribableFromOnlineConsultation(1L, 31L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(403));
    }

    @Test
    void onlineConsultationContextRejectsUnboundOrForeignConsultation() {
        givenDoctor(8L, 5L);
        // 单不存在、未绑定医生、绑定其他医生：与票 55 既有守卫一致一律 404
        when(onlineConsultationMapper.selectDetailedById(31L)).thenReturn(null);
        assertThatThrownBy(() -> service.requirePrescribableFromOnlineConsultation(8L, 31L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(404);
                    assertThat(e.getMessage()).isEqualTo("问诊单不存在");
                });

        when(onlineConsultationMapper.selectDetailedById(31L)).thenReturn(consultation("WAITING_DOCTOR", null));
        assertThatThrownBy(() -> service.requirePrescribableFromOnlineConsultation(8L, 31L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));

        when(onlineConsultationMapper.selectDetailedById(31L)).thenReturn(consultation("IN_PROGRESS", 88L));
        assertThatThrownBy(() -> service.requirePrescribableFromOnlineConsultation(8L, 31L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void onlineConsultationContextRequiresInProgress() {
        givenDoctor(8L, 5L);
        when(onlineConsultationMapper.selectDetailedById(31L)).thenReturn(consultation("COMPLETED", 5L));

        assertThatThrownBy(() -> service.requirePrescribableFromOnlineConsultation(8L, 31L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("问诊不在进行中");
                });
    }

    // ------------------------------------------------------------------
    // 已落库处方反向派生
    // ------------------------------------------------------------------

    @Test
    void prescribableRejectsDoctorWithoutCampus() {
        // 医生所属科室未配置院区：开方上下文无法派生来源院区，fail closed（票 88）
        StaffUser staff = new StaffUser();
        staff.setRole(StaffUser.ROLE_DOCTOR);
        staff.setDoctorId(5L);
        when(staffUserMapper.selectById(8L)).thenReturn(staff);
        when(doctorMapper.selectCampusIdByDoctorId(5L)).thenReturn(null);

        assertThatThrownBy(() -> service.requirePrescribableFromAppointment(8L, 21L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("医生所属科室未配置院区");
                });
    }

    @Test
    void ofPrescriptionDerivesSourceByNonNullForeignKey() {
        Prescription offline = new Prescription();
        offline.setAppointmentId(21L);
        offline.setPatientId(12L);
        offline.setHealthProfileId(3L);
        offline.setDoctorId(5L);
        assertThat(service.ofPrescription(offline).sourceType()).isEqualTo("APPOINTMENT");

        Prescription online = new Prescription();
        online.setOnlineConsultationId(31L);
        online.setPatientId(12L);
        online.setHealthProfileId(3L);
        online.setDoctorId(5L);
        online.setSourceCampusId(7L);
        ClinicalContextService.ClinicalContext context = service.ofPrescription(online);
        assertThat(context.sourceType()).isEqualTo("ONLINE_CONSULTATION");
        assertThat(context.patientId()).isEqualTo(12L);
        assertThat(context.healthProfileId()).isEqualTo(3L);
        // 来源院区取处方开方时固化的不可变列，不现查医生所属科室
        assertThat(context.sourceCampusId()).isEqualTo(7L);
    }

    private void givenDoctor(long staffId, long doctorId) {
        StaffUser staff = new StaffUser();
        staff.setRole(StaffUser.ROLE_DOCTOR);
        staff.setDoctorId(doctorId);
        when(staffUserMapper.selectById(staffId)).thenReturn(staff);
        // 票 88：开方上下文携带医生当前所属院区（科室 → 院区外键派生）
        when(doctorMapper.selectCampusIdByDoctorId(doctorId)).thenReturn(7L);
    }

    private Appointment appointment() {
        Appointment appointment = new Appointment();
        appointment.setId(21L);
        appointment.setPatientId(12L);
        appointment.setHealthProfileId(3L);
        appointment.setStatus("BOOKED");
        appointment.setCreatedAt(OffsetDateTime.parse("2026-08-01T09:00:00+08:00"));
        return appointment;
    }

    private OnlineConsultation consultation(String status, Long doctorId) {
        OnlineConsultation consultation = new OnlineConsultation();
        consultation.setId(31L);
        consultation.setPatientId(12L);
        consultation.setHealthProfileId(3L);
        consultation.setStatus(status);
        consultation.setDoctorId(doctorId);
        consultation.setCreatedAt(OffsetDateTime.parse("2026-08-01T10:00:00+08:00"));
        consultation.setAcceptedAt(doctorId == null ? null : OffsetDateTime.parse("2026-08-01T10:03:00+08:00"));
        return consultation;
    }
}

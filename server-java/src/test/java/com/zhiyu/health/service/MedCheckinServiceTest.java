package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.MedCheckinRecord;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.PrescriptionItem;
import com.zhiyu.health.mapper.AppointmentMapper;
import com.zhiyu.health.mapper.MedCheckinRecordMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.service.mapping.MedCheckinDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class MedCheckinServiceTest {

    private final MedCheckinRecordMapper checkinMapper = org.mockito.Mockito.mock(MedCheckinRecordMapper.class);
    private final PrescriptionMapper prescriptionMapper = org.mockito.Mockito.mock(PrescriptionMapper.class);
    private final PrescriptionItemMapper itemMapper = org.mockito.Mockito.mock(PrescriptionItemMapper.class);
    private final AppointmentMapper appointmentMapper = org.mockito.Mockito.mock(AppointmentMapper.class);
    private final HealthProfileService healthProfiles = org.mockito.Mockito.mock(HealthProfileService.class);
    private final MedCheckinService service = new MedCheckinService(
            checkinMapper,
            prescriptionMapper,
            itemMapper,
            appointmentMapper,
            TestDisclaimers.instance(),
            TestContracts.instance(),
            healthProfiles,
            Mappers.getMapper(MedCheckinDtoMapper.class));

    @Test
    void eagerGeneratesDailyRemindersByDurationDays() {
        // duration="5天" -> 5 条 PENDING，due_date 从今天起逐日递增。
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(prescription(31L, 21L));
        Appointment appointment = appointment(12L, 99L);
        when(appointmentMapper.selectById(21L)).thenReturn(appointment);
        when(itemMapper.selectDetailed(31L)).thenReturn(List.of(item(101L, "阿莫西林胶囊", "5天")));

        service.generateForApprovedPrescription(31L);

        // insertIgnore 被调用 5 次，due_date 分别是今天起 0..4 天。
        verify(checkinMapper).insertIgnore(argMatcher(101L, LocalDate.now()));
        verify(checkinMapper).insertIgnore(argMatcher(101L, LocalDate.now().plusDays(4)));
        verify(checkinMapper, org.mockito.Mockito.times(5)).insertIgnore(any(MedCheckinRecord.class));
    }

    @Test
    void eagerParsesWeekAndMonthDuration() {
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(prescription(31L, 21L));
        when(appointmentMapper.selectById(21L)).thenReturn(appointment(12L, 99L));
        when(itemMapper.selectDetailed(31L)).thenReturn(List.of(item(102L, "布洛芬", "2周")));

        service.generateForApprovedPrescription(31L);

        // 2周 = 14 天。
        verify(checkinMapper, org.mockito.Mockito.times(14)).insertIgnore(any(MedCheckinRecord.class));
    }

    @Test
    void eagerFallsBackToDefaultDaysWhenDurationUnparseable() {
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(prescription(31L, 21L));
        when(appointmentMapper.selectById(21L)).thenReturn(appointment(12L, 99L));
        when(itemMapper.selectDetailed(31L)).thenReturn(List.of(item(103L, "维生素C", "遵医嘱")));

        service.generateForApprovedPrescription(31L);

        // 无法解析默认 7 天。
        verify(checkinMapper, org.mockito.Mockito.times(7)).insertIgnore(any(MedCheckinRecord.class));
    }

    @Test
    void eagerSkipsWhenPrescriptionMissing() {
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(null);

        service.generateForApprovedPrescription(31L);

        verify(checkinMapper, never()).insertIgnore(any(MedCheckinRecord.class));
    }

    @Test
    void checkAdvancesPendingToCheckedAndReturnsStreak() {
        MedCheckinRecord pending = record(201L, 12L, 99L, "PENDING", null);
        MedCheckinRecord checked = record(201L, 12L, 99L, "CHECKED", OffsetDateTime.now());
        when(checkinMapper.selectOwned(201L, 12L)).thenReturn(pending, checked);
        when(checkinMapper.check(201L, "CHECKED", "PENDING")).thenReturn(1);
        // streak 现算：今天已打 -> streak=1。
        when(checkinMapper.selectCheckedDatesDescending(12L, 99L, "CHECKED")).thenReturn(List.of(LocalDate.now()));

        MedCheckinView view = service.check(12L, 201L);

        assertThat(view.status()).isEqualTo("已服用");
        assertThat(view.streak()).isEqualTo(1);
    }

    @Test
    void checkIsIdempotentOnRepeatClick() {
        // 第二次点击：UPDATE affectedRows=0（已 CHECKED），返回当前状态 + streak，不抛错。
        MedCheckinRecord checked = record(201L, 12L, 99L, "CHECKED", OffsetDateTime.now());
        when(checkinMapper.selectOwned(201L, 12L)).thenReturn(checked, checked);
        when(checkinMapper.check(201L, "CHECKED", "PENDING")).thenReturn(0);
        when(checkinMapper.selectCheckedDatesDescending(12L, 99L, "CHECKED")).thenReturn(List.of(LocalDate.now()));

        MedCheckinView view = service.check(12L, 201L);

        assertThat(view.status()).isEqualTo("已服用");
        assertThat(view.streak()).isEqualTo(1);
    }

    @Test
    void checkRejectsForeignPatientRecord() {
        // 越权：记录不属于当前 patient，selectOwned 返回 null -> 404（不泄露存在性）。
        when(checkinMapper.selectOwned(201L, 12L)).thenReturn(null);

        assertThatThrownBy(() -> service.check(12L, 201L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(404));
        verify(checkinMapper, never()).check(anyLong(), eq("CHECKED"), eq("PENDING"));
    }

    @Test
    void streakResetsToZeroOnGap() {
        // 昨天漏服：已打日期是 [今天, 前天]，今天->1，前天不连续->停，streak=1。
        when(checkinMapper.selectCheckedDatesDescending(12L, 99L, "CHECKED"))
                .thenReturn(List.of(LocalDate.now(), LocalDate.now().minusDays(2)));

        assertThat(service.streak(12L, 99L)).isEqualTo(1);
    }

    @Test
    void streakCountsConsecutiveDaysFromYesterdayWhenTodayNotChecked() {
        // 今天还没打、昨天打了：从昨天数起，连续昨天+前天 -> streak=2（今天未到点不算漏）。
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate dayBefore = LocalDate.now().minusDays(2);
        when(checkinMapper.selectCheckedDatesDescending(12L, 99L, "CHECKED")).thenReturn(List.of(yesterday, dayBefore));

        assertThat(service.streak(12L, 99L)).isEqualTo(2);
    }

    @Test
    void streakReturnsZeroWhenNoCheckedRecords() {
        when(checkinMapper.selectCheckedDatesDescending(12L, 99L, "CHECKED")).thenReturn(List.of());

        assertThat(service.streak(12L, 99L)).isEqualTo(0);
    }

    @Test
    void pendingRemindersReturnsOnlyDueAndActiveProfile() {
        when(healthProfiles.requireActive(12L)).thenReturn(profile(99L));
        when(checkinMapper.selectPendingDue(12L, 99L, LocalDate.now(), "PENDING"))
                .thenReturn(List.of(record(201L, 12L, 99L, "PENDING", null)));

        List<MedCheckinView> views = service.pendingReminders(12L);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).status()).isEqualTo("待打卡");
        assertThat(views.get(0).streak()).isNull();
    }

    private MedCheckinRecord argMatcher(long itemId, LocalDate dueDate) {
        return org.mockito.ArgumentMatchers.argThat(
                r -> r != null && r.getPrescriptionItemId() == itemId && dueDate.equals(r.getDueDate()));
    }

    private Prescription prescription(long id, long appointmentId) {
        Prescription p = new Prescription();
        p.setId(id);
        p.setAppointmentId(appointmentId);
        return p;
    }

    private Appointment appointment(long patientId, long profileId) {
        Appointment a = new Appointment();
        a.setPatientId(patientId);
        a.setHealthProfileId(profileId);
        return a;
    }

    private PrescriptionItem item(long id, String name, String duration) {
        PrescriptionItem item = new PrescriptionItem();
        item.setId(id);
        item.setMedicationName(name);
        item.setDosage("0.5g");
        item.setFrequency("每日3次");
        item.setDuration(duration);
        return item;
    }

    private MedCheckinRecord record(long id, long patientId, long profileId, String status, OffsetDateTime checkedAt) {
        MedCheckinRecord r = new MedCheckinRecord();
        r.setId(id);
        r.setPatientId(patientId);
        r.setHealthProfileId(profileId);
        r.setPrescriptionId(31L);
        r.setPrescriptionItemId(101L);
        r.setMedicationName("阿莫西林胶囊");
        r.setDosage("0.5g");
        r.setFrequency("每日3次");
        r.setDueDate(LocalDate.now());
        r.setStatus(status);
        r.setCheckedAt(checkedAt);
        r.setDisclaimer("仅供参考，不替代医生诊断");
        return r;
    }

    private com.zhiyu.health.entity.HealthProfile profile(long id) {
        com.zhiyu.health.entity.HealthProfile p = new com.zhiyu.health.entity.HealthProfile();
        p.setId(id);
        return p;
    }
}

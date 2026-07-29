package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.PrescriptionItem;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.MedicationMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.mapper.ReceptionMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.service.mapping.PrescriptionDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class PrescriptionServiceTest {
    private final StaffUserMapper staffUserMapper = mock(StaffUserMapper.class);
    private final ReceptionMapper receptionMapper = mock(ReceptionMapper.class);
    private final MedicationMapper medicationMapper = mock(MedicationMapper.class);
    private final PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
    private final PrescriptionItemMapper itemMapper = mock(PrescriptionItemMapper.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final AgentClient agentClient = mock(AgentClient.class);
    private final ContraindicationService contraindicationService = mock(ContraindicationService.class);
    private final PrescriptionService service = new PrescriptionService(
            staffUserMapper,
            receptionMapper,
            medicationMapper,
            prescriptionMapper,
            itemMapper,
            transactionTemplate,
            agentClient,
            contraindicationService,
            TestDisclaimers.instance(),
            TestContracts.instance(),
            Mappers.getMapper(PrescriptionDtoMapper.class));

    @Test
    void approvalGeneratesExplanationThenPublishesWithJavaDisclaimer() {
        Prescription pending = prescription(31L, "PENDING");
        Prescription approved = prescription(31L, "APPROVED");
        approved.setInterpretation("按医生给出的频次服用。");
        approved.setDisclaimer("仅供参考，不替代医生诊断");
        PrescriptionItem item = new PrescriptionItem();
        item.setMedicationName("阿莫西林胶囊");
        item.setSpecification("0.25g*24粒");
        item.setDosage("0.5g");
        item.setFrequency("每日3次");
        item.setDuration("5天");
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(pending, approved);
        when(itemMapper.selectDetailed(31L)).thenReturn(List.of(item));
        when(agentClient.explainPrescription(anyList()))
                .thenReturn(new AgentClient.ClinicalResponse("按医生给出的频次服用。", "不可信文案"));
        when(prescriptionMapper.review(31L, "APPROVED", null, 1L, "按医生给出的频次服用。", "仅供参考，不替代医生诊断", "PENDING"))
                .thenReturn(1);

        PrescriptionService.PrescriptionView result = service.review(1L, 31L, "APPROVE", null);

        assertEquals("已通过", result.status());
        assertEquals("仅供参考，不替代医生诊断", result.disclaimer());
        verify(agentClient).explainPrescription(anyList());
    }

    @Test
    void rejectionRequiresReasonBeforeStateChange() {
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(prescription(31L, "PENDING"));

        ApiException error = assertThrows(ApiException.class, () -> service.review(1L, 31L, "REJECT", " "));

        assertEquals(400, error.getStatus());
    }

    @Test
    void checkSafetyUsesAppointmentPatientContext() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(appointment(12L));
        ContraindicationResult safe =
                new ContraindicationResult("SAFE", "contraindication_result", false, List.of(), "未发现已知禁忌", null);
        when(contraindicationService.check(new ContraindicationService.CheckCommand(12L, List.of(1L, 2L))))
                .thenReturn(safe);

        ContraindicationResult result =
                service.checkSafety(new PrescriptionService.CheckSafetyCommand(8L, 21L, List.of(1L, 2L)));

        assertEquals("SAFE", result.decision());
        verify(contraindicationService).check(new ContraindicationService.CheckCommand(12L, List.of(1L, 2L)));
    }

    @Test
    void checkSafetyRejectsNonDoctor() {
        StaffUser admin = new StaffUser();
        admin.setRole(StaffUser.ROLE_ADMIN);
        when(staffUserMapper.selectById(1L)).thenReturn(admin);

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.checkSafety(new PrescriptionService.CheckSafetyCommand(1L, 21L, List.of(1L))));

        assertEquals(403, error.getStatus());
        verifyNoInteractions(contraindicationService);
    }

    @Test
    void checkSafetyRejectsForeignAppointment() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(null);

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.checkSafety(new PrescriptionService.CheckSafetyCommand(8L, 21L, List.of(1L))));

        assertEquals(404, error.getStatus());
        verifyNoInteractions(contraindicationService);
    }

    @Test
    void checkSafetyRejectsCancelledAppointment() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        Appointment cancelled = appointment(12L);
        cancelled.setStatus(Appointment.STATUS_CANCELLED);
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(cancelled);

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.checkSafety(new PrescriptionService.CheckSafetyCommand(8L, 21L, List.of(1L))));

        assertEquals(409, error.getStatus());
        verifyNoInteractions(contraindicationService);
    }

    @Test
    void createRejectsBlockedSubmissionBeforeInsert() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(appointment(12L));
        when(medicationMapper.selectById(1L)).thenReturn(medication(1L));
        when(contraindicationService.check(new ContraindicationService.CheckCommand(12L, List.of(1L))))
                .thenReturn(new ContraindicationResult(
                        "BLOCKED",
                        "contraindication_warning",
                        true,
                        List.of("过敏史“青霉素”与药品 1 的成分/禁忌项匹配"),
                        "检测到用药禁忌，已阻止本次药品推荐。请咨询医生或药师后再用药。",
                        "请咨询医生或药师，并主动告知完整过敏史和正在使用的药品。"));

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.create(new PrescriptionService.CreateCommand(
                        8L, 21L, null, List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", null)))));

        assertEquals(409, error.getStatus());
        assertEquals("检测到用药禁忌，已阻止本次处方提交。请调整用药方案或咨询药师：过敏史“青霉素”与药品 1 的成分/禁忌项匹配", error.getMessage());
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    void createChecksSafetyThenPersistsWhenSafe() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(appointment(12L));
        when(medicationMapper.selectById(1L)).thenReturn(medication(1L));
        when(contraindicationService.check(new ContraindicationService.CheckCommand(12L, List.of(1L))))
                .thenReturn(new ContraindicationResult(
                        "SAFE", "contraindication_result", false, List.of(), "未发现已知禁忌", null));
        // 测试替身直接执行事务回调，等价于真实事务模板的行为。
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        doAnswer(invocation -> {
                    invocation.getArgument(0, Prescription.class).setId(31L);
                    return 1;
                })
                .when(prescriptionMapper)
                .insert(any(Prescription.class));
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(prescription(31L, "PENDING"));

        PrescriptionService.PrescriptionView view = service.create(new PrescriptionService.CreateCommand(
                8L, 21L, null, List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", null))));

        assertEquals("待审核", view.status());
        verify(contraindicationService).check(new ContraindicationService.CheckCommand(12L, List.of(1L)));
        verify(itemMapper).insert(any(PrescriptionItem.class));
    }

    private StaffUser doctor(long doctorId) {
        StaffUser staff = new StaffUser();
        staff.setRole(StaffUser.ROLE_DOCTOR);
        staff.setDoctorId(doctorId);
        return staff;
    }

    private Appointment appointment(long patientId) {
        Appointment appointment = new Appointment();
        appointment.setId(21L);
        appointment.setPatientId(patientId);
        appointment.setDoctorId(5L);
        appointment.setStatus(Appointment.STATUS_BOOKED);
        return appointment;
    }

    private Medication medication(long id) {
        Medication medication = new Medication();
        medication.setId(id);
        medication.setIsActive(true);
        return medication;
    }

    private Prescription prescription(long id, String status) {
        Prescription prescription = new Prescription();
        prescription.setId(id);
        prescription.setAppointmentId(21L);
        prescription.setStatus(status);
        return prescription;
    }
}

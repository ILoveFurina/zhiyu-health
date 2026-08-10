package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.appointment.Appointment;
import com.zhiyu.health.entity.common.InAppMessage;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.entity.prescription.PrescriptionItem;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.mapper.consultation.ReceptionMapper;
import com.zhiyu.health.mapper.organization.DoctorMapper;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.service.consultation.ClinicalContextService;
import com.zhiyu.health.service.prescription.ContraindicationService;
import com.zhiyu.health.service.prescription.PrescriptionService;
import com.zhiyu.health.service.prescription.mapping.PrescriptionDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class PrescriptionServiceTest {
    private final StaffUserMapper staffUserMapper = mock(StaffUserMapper.class);
    private final ReceptionMapper receptionMapper = mock(ReceptionMapper.class);
    private final OnlineConsultationMapper onlineConsultationMapper = mock(OnlineConsultationMapper.class);
    private final DoctorMapper doctorMapper = mock(DoctorMapper.class);
    private final PharmacyMedicationMapper pharmacyMedicationMapper = mock(PharmacyMedicationMapper.class);
    private final PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
    private final PrescriptionItemMapper itemMapper = mock(PrescriptionItemMapper.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final AgentClient agentClient = mock(AgentClient.class);
    private final ContraindicationService contraindicationService = mock(ContraindicationService.class);
    private final InAppMessageMapper inAppMessageMapper = mock(InAppMessageMapper.class);

    private static final long DOCTOR_CAMPUS_ID = 7L;

    {
        // 测试替身直接执行事务回调，等价于真实事务模板的行为（review 的状态推进+消息写入在事务内）。
        // 个别用例会再 stub 覆盖本打桩，再 stub 时 Mockito 会以 null 实参回放旧 answer，故对 null 容错。
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback == null ? null : callback.doInTransaction(mock(TransactionStatus.class));
        });
        // 开方医生默认属于院区 7（plain mock 无严格桩检查，个别用例可再 stub 覆盖）。
        when(doctorMapper.selectCampusIdByDoctorId(anyLong())).thenReturn(DOCTOR_CAMPUS_ID);
    }

    // 临床上下文用真实模块接同一批 mapper mock：线下路径的既有打桩（receptionMapper 等）原样生效。
    private final ClinicalContextService clinicalContexts = new ClinicalContextService(
            staffUserMapper, receptionMapper, onlineConsultationMapper, doctorMapper, TestContracts.instance());
    private final PrescriptionService service = new PrescriptionService(
            prescriptionMapper,
            itemMapper,
            transactionTemplate,
            agentClient,
            contraindicationService,
            TestDisclaimers.instance(),
            TestContracts.instance(),
            Mappers.getMapper(PrescriptionDtoMapper.class),
            clinicalContexts,
            inAppMessageMapper,
            pharmacyMedicationMapper);

    @Test
    void listForReviewPassesStatusAndKeywordToMapper() {
        // status=null 表示全部状态，keyword 透传（trim 后非空）；非空 status 命中契约枚举放行。
        Prescription pending = prescription(31L, "PENDING");
        pending.setPatientId(12L);
        when(prescriptionMapper.selectForReview(null, "林")).thenReturn(List.of(pending));
        when(prescriptionMapper.selectForReview("PENDING", null)).thenReturn(List.of(pending));

        List<PrescriptionService.PrescriptionView> all = service.listForReview(null, " 林 ");
        List<PrescriptionService.PrescriptionView> pendingOnly = service.listForReview("PENDING", "  ");

        assertEquals(1, all.size());
        assertEquals(1, pendingOnly.size());
        // keyword 经 trim+blank 归一：纯空白归为 null，非空白 trim 后透传
        verify(prescriptionMapper).selectForReview(null, "林");
        verify(prescriptionMapper).selectForReview("PENDING", null);
    }

    @Test
    void listForReviewRejectsUnknownStatus() {
        // 非空 status 必须命中契约枚举；空 status 表示全部，不校验。
        ApiException unknown = assertThrows(ApiException.class, () -> service.listForReview("UNKNOWN", null));
        assertEquals(400, unknown.getStatus());

        // 空白 status 等价于全部，不抛 400
        service.listForReview("  ", null);
        verify(prescriptionMapper).selectForReview(null, null);
    }

    @Test
    void approvalGeneratesExplanationThenPublishesWithJavaDisclaimer() {
        Prescription pending = prescription(31L, "PENDING");
        Prescription approved = prescription(31L, "APPROVED");
        approved.setPatientId(12L);
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
        // 票 88（ADR-0035）：审核通过不再生成用药提醒；改由处方药订单交付终态触发。
        // 票 60：通过分支同事务写审核结果站内消息，文案只取契约
        var copy = TestContracts.instance().prescriptionFlow().messages().get("approved");
        ArgumentCaptor<InAppMessage> message = ArgumentCaptor.forClass(InAppMessage.class);
        verify(inAppMessageMapper).insertIgnoreConflict(message.capture());
        assertEquals(12L, message.getValue().getPatientId());
        assertEquals(
                TestContracts.instance().prescriptionFlow().messageTypes().get("prescription_review_result"),
                message.getValue().getType());
        assertEquals(copy.title(), message.getValue().getTitle());
        assertEquals(copy.content(), message.getValue().getContent());
        assertEquals("仅供参考，不替代医生诊断", message.getValue().getDisclaimer());
        assertEquals(31L, message.getValue().getRelatedPrescriptionId());
    }

    @Test
    void rejectionWritesReviewResultMessageFromContract() {
        // 票 60：驳回分支同样写审核结果站内消息（取 rejected 文案）
        Prescription pending = prescription(31L, "PENDING");
        Prescription rejected = prescription(31L, "REJECTED");
        rejected.setPatientId(12L);
        rejected.setReviewReason("用法不当");
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(pending, rejected);
        when(prescriptionMapper.review(31L, "REJECTED", "用法不当", 1L, null, null, "PENDING"))
                .thenReturn(1);

        service.review(1L, 31L, "REJECT", "用法不当");

        var copy = TestContracts.instance().prescriptionFlow().messages().get("rejected");
        ArgumentCaptor<InAppMessage> message = ArgumentCaptor.forClass(InAppMessage.class);
        verify(inAppMessageMapper).insertIgnoreConflict(message.capture());
        assertEquals(copy.title(), message.getValue().getTitle());
        assertEquals(copy.content(), message.getValue().getContent());
        assertEquals(31L, message.getValue().getRelatedPrescriptionId());
    }

    @Test
    void duplicateReviewKeeps409AndSkipsMessage() {
        // 重复审核：既有的 409 语义不动，且不得重复写消息
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(prescription(31L, "APPROVED"));

        ApiException error = assertThrows(ApiException.class, () -> service.review(1L, 31L, "APPROVE", null));

        assertEquals(409, error.getStatus());
        verify(inAppMessageMapper, never()).insertIgnoreConflict(any(InAppMessage.class));
        // 并发落败：条件更新 0 行同样 409、不写消息、不冒 500
        when(prescriptionMapper.selectDetailedById(32L)).thenReturn(prescription(32L, "PENDING"));
        when(itemMapper.selectDetailed(32L)).thenReturn(List.of());
        when(agentClient.explainPrescription(anyList())).thenReturn(new AgentClient.ClinicalResponse("解读", "不可信文案"));
        when(prescriptionMapper.review(anyLong(), anyString(), any(), anyLong(), any(), any(), anyString()))
                .thenReturn(0);

        ApiException conflict = assertThrows(ApiException.class, () -> service.review(1L, 32L, "APPROVE", null));

        assertEquals(409, conflict.getStatus());
        verify(inAppMessageMapper, never()).insertIgnoreConflict(any(InAppMessage.class));
    }

    @Test
    void reviewMessageUniqueCollisionIsSwallowedAsDelivered() {
        // 票 60：消息撞 UNIQUE(related_prescription_id, type)（并发/重试极端竞态）由 ON CONFLICT DO NOTHING
        // 在数据库层幂等吞掉（返回 0 行、事务不受损），审核结果照常返回，不冒 500
        Prescription pending = prescription(31L, "PENDING");
        Prescription rejected = prescription(31L, "REJECTED");
        rejected.setPatientId(12L);
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(pending, rejected);
        when(prescriptionMapper.review(31L, "REJECTED", "用法不当", 1L, null, null, "PENDING"))
                .thenReturn(1);
        when(inAppMessageMapper.insertIgnoreConflict(any(InAppMessage.class))).thenReturn(0);

        PrescriptionService.PrescriptionView result = service.review(1L, 31L, "REJECT", "用法不当");

        assertEquals("已驳回", result.status());
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
    void listMedicationsOnlyReturnsOnSaleCampusCatalog() {
        // 票 88：医生开方目录只含当前院区药房已配置且在售的标准药品；
        // 过滤口径在 selectOnSaleCatalogByCampus（JOIN 药房 + is_on_sale=TRUE），service 直接透传。
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        Medication onSale = new Medication();
        onSale.setId(1L);
        when(pharmacyMedicationMapper.selectOnSaleCatalogByCampus(DOCTOR_CAMPUS_ID, null))
                .thenReturn(List.of(onSale));

        List<PrescriptionService.MedicationView> views = service.listMedications(8L, null);

        assertEquals(1, views.size());
        assertEquals(1L, views.get(0).id());
        verify(pharmacyMedicationMapper).selectOnSaleCatalogByCampus(DOCTOR_CAMPUS_ID, null);
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
        cancelled.setStatus("CANCELLED");
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
        when(pharmacyMedicationMapper.selectOnSaleByCampusAndIds(anyLong(), anyList()))
                .thenReturn(List.of(medication(1L)));
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
                        8L,
                        21L,
                        null,
                        List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", 2, null)))));

        assertEquals(409, error.getStatus());
        assertEquals("检测到用药禁忌，已阻止本次处方提交。请调整用药方案或咨询药师：过敏史“青霉素”与药品 1 的成分/禁忌项匹配", error.getMessage());
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    void createChecksSafetyThenPersistsWhenSafe() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(appointment(12L));
        when(pharmacyMedicationMapper.selectOnSaleByCampusAndIds(anyLong(), anyList()))
                .thenReturn(List.of(medication(1L)));
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
                8L, 21L, null, List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", 2, null))));

        assertEquals("待审核", view.status());
        verify(contraindicationService).check(new ContraindicationService.CheckCommand(12L, List.of(1L)));
        verify(itemMapper).insert(any(PrescriptionItem.class));
    }

    @Test
    void createFromOnlineConsultationWritesOnlineForeignKeyAndSourceType() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(onlineConsultationMapper.selectDetailedById(41L)).thenReturn(onlineConsultation("IN_PROGRESS", 5L));
        when(pharmacyMedicationMapper.selectOnSaleByCampusAndIds(anyLong(), anyList()))
                .thenReturn(List.of(medication(1L)));
        when(contraindicationService.check(new ContraindicationService.CheckCommand(12L, List.of(1L))))
                .thenReturn(new ContraindicationResult(
                        "SAFE", "contraindication_result", false, List.of(), "未发现已知禁忌", null));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        doAnswer(invocation -> {
                    invocation.getArgument(0, Prescription.class).setId(51L);
                    return 1;
                })
                .when(prescriptionMapper)
                .insert(any(Prescription.class));
        Prescription stored = onlinePrescription(51L, "PENDING");
        when(prescriptionMapper.selectDetailedById(51L)).thenReturn(stored);

        // 命令只携带 staffId 与问诊单 ID：患者/档案/医生身份全部由临床上下文派生，不接受请求体
        PrescriptionService.PrescriptionView view =
                service.createFromOnlineConsultation(new PrescriptionService.CreateOnlineCommand(
                        8L,
                        41L,
                        "足疗程服用",
                        List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", 2, null))));

        ArgumentCaptor<Prescription> inserted = ArgumentCaptor.forClass(Prescription.class);
        verify(prescriptionMapper).insert(inserted.capture());
        assertEquals(41L, inserted.getValue().getOnlineConsultationId());
        assertEquals(null, inserted.getValue().getAppointmentId());
        assertEquals(5L, inserted.getValue().getDoctorId());
        // 来源院区开方时从接诊医生所属院区固化，不接受请求体传入
        assertEquals(DOCTOR_CAMPUS_ID, inserted.getValue().getSourceCampusId());
        assertEquals("ONLINE_CONSULTATION", view.sourceType());
        assertEquals("在线问诊", view.sourceTypeLabel());
        assertEquals("待审核", view.status());
        // 禁忌复跑用的是问诊单派生的患者身份
        verify(contraindicationService).check(new ContraindicationService.CheckCommand(12L, List.of(1L)));
    }

    @Test
    void createFromOnlineConsultationRejectsDuplicate() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(onlineConsultationMapper.selectDetailedById(41L)).thenReturn(onlineConsultation("IN_PROGRESS", 5L));
        when(prescriptionMapper.selectByOnlineConsultationId(41L)).thenReturn(onlinePrescription(51L, "PENDING"));

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.createFromOnlineConsultation(new PrescriptionService.CreateOnlineCommand(
                        8L,
                        41L,
                        null,
                        List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", 2, null)))));

        assertEquals(409, error.getStatus());
        assertEquals("该问诊已开具电子处方", error.getMessage());
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
        verifyNoInteractions(contraindicationService);
    }

    @Test
    void createFromOnlineConsultationTranslatesConcurrentUniqueCollisionToConflict() {
        // 并发重复提交越过预检撞 uq_prescriptions_online_consultation：明确 409，不冒 500
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(onlineConsultationMapper.selectDetailedById(41L)).thenReturn(onlineConsultation("IN_PROGRESS", 5L));
        when(pharmacyMedicationMapper.selectOnSaleByCampusAndIds(anyLong(), anyList()))
                .thenReturn(List.of(medication(1L)));
        when(contraindicationService.check(new ContraindicationService.CheckCommand(12L, List.of(1L))))
                .thenReturn(new ContraindicationResult(
                        "SAFE", "contraindication_result", false, List.of(), "未发现已知禁忌", null));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        when(prescriptionMapper.insert(any(Prescription.class)))
                .thenThrow(new DataIntegrityViolationException("uq_prescriptions_online_consultation"));

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.createFromOnlineConsultation(new PrescriptionService.CreateOnlineCommand(
                        8L,
                        41L,
                        null,
                        List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", 2, null)))));

        assertEquals(409, error.getStatus());
        assertEquals("该问诊已开具电子处方", error.getMessage());
    }

    @Test
    void createFromOnlineConsultationRejectsBlockedSubmission() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(onlineConsultationMapper.selectDetailedById(41L)).thenReturn(onlineConsultation("IN_PROGRESS", 5L));
        when(pharmacyMedicationMapper.selectOnSaleByCampusAndIds(anyLong(), anyList()))
                .thenReturn(List.of(medication(1L)));
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
                () -> service.createFromOnlineConsultation(new PrescriptionService.CreateOnlineCommand(
                        8L,
                        41L,
                        null,
                        List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", 2, null)))));

        assertEquals(409, error.getStatus());
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    void createFromOnlineConsultationRejectsUnboundDoctorAndNotInProgress() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(onlineConsultationMapper.selectDetailedById(41L)).thenReturn(onlineConsultation("IN_PROGRESS", 88L));
        ApiException foreign = assertThrows(
                ApiException.class,
                () -> service.checkSafetyFromOnlineConsultation(
                        new PrescriptionService.CheckSafetyOnlineCommand(8L, 41L, List.of(1L))));
        assertEquals(404, foreign.getStatus());

        when(onlineConsultationMapper.selectDetailedById(41L)).thenReturn(onlineConsultation("COMPLETED", 5L));
        ApiException notInProgress = assertThrows(
                ApiException.class,
                () -> service.checkSafetyFromOnlineConsultation(
                        new PrescriptionService.CheckSafetyOnlineCommand(8L, 41L, List.of(1L))));
        assertEquals(409, notInProgress.getStatus());
        assertEquals("问诊不在进行中", notInProgress.getMessage());
        verifyNoInteractions(contraindicationService);
    }

    @Test
    void checkSafetyFromOnlineConsultationUsesDerivedPatientContext() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(onlineConsultationMapper.selectDetailedById(41L)).thenReturn(onlineConsultation("IN_PROGRESS", 5L));
        ContraindicationResult safe =
                new ContraindicationResult("SAFE", "contraindication_result", false, List.of(), "未发现已知禁忌", null);
        when(contraindicationService.check(new ContraindicationService.CheckCommand(12L, List.of(1L, 2L))))
                .thenReturn(safe);

        ContraindicationResult result = service.checkSafetyFromOnlineConsultation(
                new PrescriptionService.CheckSafetyOnlineCommand(8L, 41L, List.of(1L, 2L)));

        assertEquals("SAFE", result.decision());
        verify(contraindicationService).check(new ContraindicationService.CheckCommand(12L, List.of(1L, 2L)));
    }

    @Test
    void createRejectsMedicationNotOnSaleInSourceCampus() {
        // 提交侧复验：药品不在开方院区药房在售目录（含已下架/跨院区目录）一律 400，不落库。
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(appointment(12L));
        when(pharmacyMedicationMapper.selectOnSaleByCampusAndIds(anyLong(), anyList()))
                .thenReturn(List.of());

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.create(new PrescriptionService.CreateCommand(
                        8L,
                        21L,
                        null,
                        List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", 2, null)))));

        assertEquals(400, error.getStatus());
        assertEquals("药品不在本院区药房在售目录", error.getMessage());
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
        verifyNoInteractions(contraindicationService, transactionTemplate);
    }

    @Test
    void createRejectsNonPositiveQuantity() {
        // 配药数量必须为正整数（请求体 @Positive 之外 service 再兜底，DB CHECK 为最终防线）。
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(5L));
        when(receptionMapper.selectAppointment(21L, 5L)).thenReturn(appointment(12L));
        when(pharmacyMedicationMapper.selectOnSaleByCampusAndIds(anyLong(), anyList()))
                .thenReturn(List.of(medication(1L)));

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.create(new PrescriptionService.CreateCommand(
                        8L,
                        21L,
                        null,
                        List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", 0, null)))));

        assertEquals(400, error.getStatus());
        assertEquals("配药数量必须为正整数", error.getMessage());
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
        verifyNoInteractions(contraindicationService, transactionTemplate);
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
        appointment.setStatus("BOOKED");
        return appointment;
    }

    private Medication medication(long id) {
        Medication medication = new Medication();
        medication.setId(id);
        return medication;
    }

    private OnlineConsultation onlineConsultation(String status, Long doctorId) {
        OnlineConsultation consultation = new OnlineConsultation();
        consultation.setId(41L);
        consultation.setPatientId(12L);
        consultation.setHealthProfileId(3L);
        consultation.setStatus(status);
        consultation.setDoctorId(doctorId);
        consultation.setCreatedAt(java.time.OffsetDateTime.parse("2026-08-01T10:00:00+08:00"));
        return consultation;
    }

    private Prescription onlinePrescription(long id, String status) {
        Prescription prescription = new Prescription();
        prescription.setId(id);
        prescription.setOnlineConsultationId(41L);
        prescription.setStatus(status);
        return prescription;
    }

    private Prescription prescription(long id, String status) {
        Prescription prescription = new Prescription();
        prescription.setId(id);
        prescription.setAppointmentId(21L);
        prescription.setStatus(status);
        return prescription;
    }
}

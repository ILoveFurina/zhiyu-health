package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.PrescriptionItem;
import com.zhiyu.health.mapper.MedicationMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.mapper.ReceptionMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
import com.zhiyu.health.support.TestDisclaimers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class PrescriptionServiceTest {
    private final PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
    private final PrescriptionItemMapper itemMapper = mock(PrescriptionItemMapper.class);
    private final AgentClient agentClient = mock(AgentClient.class);
    private final PrescriptionService service = new PrescriptionService(
            mock(StaffUserMapper.class),
            mock(ReceptionMapper.class),
            mock(MedicationMapper.class),
            prescriptionMapper,
            itemMapper,
            mock(TransactionTemplate.class),
            agentClient,
            TestDisclaimers.instance());

    @Test
    void approvalGeneratesExplanationThenPublishesWithJavaDisclaimer() {
        Prescription pending = prescription(31L, Prescription.STATUS_PENDING);
        Prescription approved = prescription(31L, Prescription.STATUS_APPROVED);
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
        when(prescriptionMapper.review(31L, Prescription.STATUS_APPROVED, null, 1L, "按医生给出的频次服用。", "仅供参考，不替代医生诊断"))
                .thenReturn(1);

        PrescriptionService.PrescriptionView result = service.review(1L, 31L, "APPROVE", null);

        assertEquals("已通过", result.status());
        assertEquals("仅供参考，不替代医生诊断", result.disclaimer());
        verify(agentClient).explainPrescription(anyList());
    }

    @Test
    void rejectionRequiresReasonBeforeStateChange() {
        when(prescriptionMapper.selectDetailedById(31L)).thenReturn(prescription(31L, Prescription.STATUS_PENDING));

        ApiException error = assertThrows(ApiException.class, () -> service.review(1L, 31L, "REJECT", " "));

        assertEquals(400, error.getStatus());
    }

    private Prescription prescription(long id, String status) {
        Prescription prescription = new Prescription();
        prescription.setId(id);
        prescription.setAppointmentId(21L);
        prescription.setStatus(status);
        return prescription;
    }
}

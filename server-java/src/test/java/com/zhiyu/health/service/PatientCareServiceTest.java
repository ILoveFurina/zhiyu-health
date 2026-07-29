package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.mapper.InAppMessageMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatientCareServiceTest {
    @Test
    void patientPrescriptionListUsesApprovedOnlyMapperBoundary() {
        PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
        PrescriptionItemMapper itemMapper = mock(PrescriptionItemMapper.class);
        PatientCareService service =
                new PatientCareService(prescriptionMapper, itemMapper, mock(InAppMessageMapper.class));
        Prescription approved = new Prescription();
        approved.setId(31L);
        approved.setStatus(Prescription.STATUS_APPROVED);
        when(prescriptionMapper.selectApprovedForPatient(7L)).thenReturn(List.of(approved));
        when(itemMapper.selectDetailed(31L)).thenReturn(List.of());

        assertEquals(1, service.approvedPrescriptions(7L).size());
        verify(prescriptionMapper).selectApprovedForPatient(7L);
    }
}

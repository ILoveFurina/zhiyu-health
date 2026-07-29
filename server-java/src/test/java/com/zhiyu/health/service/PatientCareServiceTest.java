package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.mapper.InAppMessageMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.service.mapping.PrescriptionDtoMapper;
import com.zhiyu.health.support.TestContracts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class PatientCareServiceTest {
    @Test
    void patientPrescriptionListUsesApprovedOnlyMapperBoundary() {
        PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
        PrescriptionItemMapper itemMapper = mock(PrescriptionItemMapper.class);
        PatientCareService service = new PatientCareService(
                prescriptionMapper,
                itemMapper,
                mock(InAppMessageMapper.class),
                TestContracts.instance(),
                Mappers.getMapper(PrescriptionDtoMapper.class));
        Prescription approved = new Prescription();
        approved.setId(31L);
        approved.setStatus("APPROVED");
        when(prescriptionMapper.selectApprovedForPatient(7L, "APPROVED")).thenReturn(List.of(approved));
        when(itemMapper.selectDetailed(31L)).thenReturn(List.of());

        assertEquals(1, service.approvedPrescriptions(7L).size());
        verify(prescriptionMapper).selectApprovedForPatient(7L, "APPROVED");
    }
}

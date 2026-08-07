package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.mapper.InAppMessageMapper;
import com.zhiyu.health.mapper.OnlineConsultationMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.mapper.ReceptionMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
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
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        HealthProfile profile = new HealthProfile();
        profile.setId(41L);
        when(healthProfiles.requireActive(7L)).thenReturn(profile);
        PatientCareService service = new PatientCareService(
                prescriptionMapper,
                itemMapper,
                mock(InAppMessageMapper.class),
                TestContracts.instance(),
                Mappers.getMapper(PrescriptionDtoMapper.class),
                healthProfiles,
                clinicalContexts());
        Prescription approved = new Prescription();
        approved.setId(31L);
        approved.setStatus("APPROVED");
        when(prescriptionMapper.selectApprovedForProfile(7L, 41L, "APPROVED")).thenReturn(List.of(approved));
        when(itemMapper.selectDetailed(31L)).thenReturn(List.of());

        assertEquals(1, service.approvedPrescriptions(7L).size());
        verify(prescriptionMapper).selectApprovedForProfile(7L, 41L, "APPROVED");
    }

    @Test
    void onlineConsultationPrescriptionCarriesContractSourceType() {
        // 在线问诊处方：source_type 按非空外键派生且只取契约值（票 55），PENDING/REJECTED
        // 不出队由 selectApprovedForProfile 的 SQL 边界保证（本测试钉住调用边界与派生值）。
        PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
        PrescriptionItemMapper itemMapper = mock(PrescriptionItemMapper.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        HealthProfile profile = new HealthProfile();
        profile.setId(41L);
        when(healthProfiles.requireActive(7L)).thenReturn(profile);
        PatientCareService service = new PatientCareService(
                prescriptionMapper,
                itemMapper,
                mock(InAppMessageMapper.class),
                TestContracts.instance(),
                Mappers.getMapper(PrescriptionDtoMapper.class),
                healthProfiles,
                clinicalContexts());
        Prescription online = new Prescription();
        online.setId(32L);
        online.setOnlineConsultationId(55L);
        online.setStatus("APPROVED");
        when(prescriptionMapper.selectApprovedForProfile(7L, 41L, "APPROVED")).thenReturn(List.of(online));
        when(itemMapper.selectDetailed(32L)).thenReturn(List.of());

        List<PatientCareService.PatientPrescriptionView> views = service.approvedPrescriptions(7L);

        assertEquals(1, views.size());
        assertEquals(
                TestContracts.instance().prescriptionFlow().sourceTypes().get("online_consultation"),
                views.get(0).sourceType());
    }

    private static ClinicalContextService clinicalContexts() {
        return new ClinicalContextService(
                mock(StaffUserMapper.class),
                mock(ReceptionMapper.class),
                mock(OnlineConsultationMapper.class),
                TestContracts.instance());
    }
}

package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.entity.health.HealthProfile;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.mapper.consultation.ReceptionMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.consultation.ClinicalContextService;
import com.zhiyu.health.service.consultation.PatientCareService;
import com.zhiyu.health.service.health.HealthProfileService;
import com.zhiyu.health.service.prescription.mapping.PrescriptionDtoMapper;
import com.zhiyu.health.support.TestContracts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class PatientCareServiceTest {
    @Test
    void patientPrescriptionListCoversAllStatusesWithoutMapperFilter() {
        // 票 60：查询层不再按 APPROVED 过滤；用药解读只随 APPROVED 落库，非 APPROVED 天然为 null
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
        Prescription pending = new Prescription();
        pending.setId(31L);
        pending.setAppointmentId(21L);
        pending.setStatus("PENDING");
        when(prescriptionMapper.selectForProfile(7L, 41L)).thenReturn(List.of(pending));
        when(itemMapper.selectDetailed(31L)).thenReturn(List.of());

        List<PatientCareService.PatientPrescriptionView> views = service.prescriptions(7L);

        assertEquals(1, views.size());
        assertEquals("PENDING", views.get(0).status());
        assertEquals(
                TestContracts.instance().prescriptionFlow().statusLabels().get("PENDING"),
                views.get(0).statusLabel());
        // 来源单号取 appointment/online_consultation 两外键中非空者（问诊完成页匹配本单处方）
        assertEquals(21L, views.get(0).sourceId());
        assertNull(views.get(0).interpretation());
        verify(prescriptionMapper).selectForProfile(7L, 41L);
    }

    @Test
    void rejectedOnlinePrescriptionCarriesStatusLabelReasonAndSourceId() {
        // 在线问诊处方（票 56/60）：source_type 按非空外键派生且只取契约值；驳回处方带出驳回原因
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
        online.setStatus("REJECTED");
        online.setReviewReason("用法用量需调整");
        when(prescriptionMapper.selectForProfile(7L, 41L)).thenReturn(List.of(online));
        when(itemMapper.selectDetailed(32L)).thenReturn(List.of());

        List<PatientCareService.PatientPrescriptionView> views = service.prescriptions(7L);

        assertEquals(1, views.size());
        assertEquals(
                TestContracts.instance().prescriptionFlow().sourceTypes().get("online_consultation"),
                views.get(0).sourceType());
        assertEquals(
                TestContracts.instance().prescriptionFlow().statusLabels().get("REJECTED"),
                views.get(0).statusLabel());
        assertEquals("用法用量需调整", views.get(0).reviewReason());
        assertEquals(55L, views.get(0).sourceId());
    }

    private static ClinicalContextService clinicalContexts() {
        return new ClinicalContextService(
                mock(StaffUserMapper.class),
                mock(ReceptionMapper.class),
                mock(OnlineConsultationMapper.class),
                TestContracts.instance());
    }
}

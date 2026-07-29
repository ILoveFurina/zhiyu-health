package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.mapper.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.HealthProfileMapper;
import com.zhiyu.health.mapper.MedicationMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.rule.ContraindicationFactRepository;
import com.zhiyu.health.rule.ContraindicationFacts;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.rule.ContraindicationRuleEngine;
import com.zhiyu.health.rule.MedicationContraindicationFact;
import com.zhiyu.health.rule.MedicationInteractionFact;
import com.zhiyu.health.support.TestContracts;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContraindicationServiceTest {

    private final HealthProfileMapper profileMapper = mock(HealthProfileMapper.class);
    private final HealthProfileAllergyMapper allergyMapper = mock(HealthProfileAllergyMapper.class);
    private final MedicationMapper medicationMapper = mock(MedicationMapper.class);
    private final PrescriptionItemMapper prescriptionItemMapper = mock(PrescriptionItemMapper.class);
    private final ContraindicationFactRepository facts = mock(ContraindicationFactRepository.class);
    private final ContraindicationService service = new ContraindicationService(
            profileMapper,
            allergyMapper,
            medicationMapper,
            prescriptionItemMapper,
            facts,
            new ContraindicationRuleEngine(TestContracts.instance()),
            TestContracts.instance());

    @Test
    void checksCurrentProfilesAllergiesAgainstValidatedMedicationIds() {
        HealthProfile profile = profile(31L);
        Medication medication = medication(1L);
        when(profileMapper.selectActive(12L)).thenReturn(profile);
        when(allergyMapper.selectAllergens(31L)).thenReturn(List.of("青霉素"));
        when(medicationMapper.selectByIds(List.of(1L))).thenReturn(List.of(medication));
        when(facts.load(List.of(1L)))
                .thenReturn(new ContraindicationFacts(
                        List.of(new MedicationContraindicationFact(1L, List.of("阿莫西林"), List.of("青霉素"))),
                        List.of(),
                        true));

        assertThat(service.check(new ContraindicationService.CheckCommand(12L, List.of(1L)))
                        .decision())
                .isEqualTo("BLOCKED");
    }

    @Test
    void rejectsWhenPatientHasNoCurrentHealthProfile() {
        when(profileMapper.selectActive(12L)).thenReturn(null);

        assertThatThrownBy(() -> service.check(new ContraindicationService.CheckCommand(12L, List.of(1L))))
                .isInstanceOf(ApiException.class)
                .hasMessage("请先创建并激活健康档案后再进行禁忌检查");
    }

    @Test
    void rejectsUnknownMedicationIdBeforeReadingNeo4j() {
        when(profileMapper.selectActive(12L)).thenReturn(profile(31L));
        when(medicationMapper.selectByIds(List.of(999L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.check(new ContraindicationService.CheckCommand(12L, List.of(999L))))
                .isInstanceOf(ApiException.class)
                .hasMessage("药品不存在或已停用: 999");
    }

    @Test
    void blocksForReviewWhenNeo4jFactsCannotBeRead() {
        when(profileMapper.selectActive(12L)).thenReturn(profile(31L));
        when(allergyMapper.selectAllergens(31L)).thenReturn(List.of());
        when(medicationMapper.selectByIds(List.of(1L))).thenReturn(List.of(medication(1L)));
        when(facts.load(List.of(1L))).thenThrow(new IllegalStateException("Neo4j unavailable"));

        ContraindicationService.CheckCommand command = new ContraindicationService.CheckCommand(12L, List.of(1L));
        assertThat(service.check(command).decision()).isEqualTo("REVIEW_REQUIRED");
        assertThat(service.check(command).blocked()).isTrue();
    }

    @Test
    void checksCandidateAgainstCurrentApprovedPrescription() {
        when(profileMapper.selectActive(12L)).thenReturn(profile(31L));
        when(allergyMapper.selectAllergens(31L)).thenReturn(List.of());
        when(medicationMapper.selectByIds(List.of(2L))).thenReturn(List.of(medication(2L)));
        when(prescriptionItemMapper.selectMedicationIdsByHealthProfileAndStatus(31L, "APPROVED"))
                .thenReturn(List.of(4L));
        when(facts.load(List.of(2L, 4L)))
                .thenReturn(new ContraindicationFacts(
                        List.of(
                                new MedicationContraindicationFact(2L, List.of("布洛芬"), List.of()),
                                new MedicationContraindicationFact(4L, List.of("华法林"), List.of())),
                        List.of(new MedicationInteractionFact(2L, 4L, "合用可能增加出血风险")),
                        true));

        ContraindicationResult result = service.check(new ContraindicationService.CheckCommand(12L, List.of(2L)));

        assertThat(result.decision()).isEqualTo("BLOCKED");
    }

    private HealthProfile profile(long id) {
        HealthProfile profile = new HealthProfile();
        profile.setId(id);
        return profile;
    }

    private Medication medication(long id) {
        Medication medication = new Medication();
        medication.setId(id);
        medication.setIsActive(true);
        return medication;
    }
}

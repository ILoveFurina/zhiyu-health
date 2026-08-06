package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.mapper.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.HealthProfileMapper;
import com.zhiyu.health.mapper.MedicationMapper;
import com.zhiyu.health.rule.ContraindicationFactRepository;
import com.zhiyu.health.rule.ContraindicationFacts;
import com.zhiyu.health.rule.ContraindicationRuleEngine;
import com.zhiyu.health.rule.MedicationContraindicationFact;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 拍药盒与文字查药的规则引擎组装（票 14，ADR-0025 差异化点 2/3）。
 *
 * <p>验证：双列查匹配、未匹配 text 引导、无档案优雅降级、Neo4j 不可用 fail-closed、
 * 命中禁忌 blocked 双出口、不注入 PrescriptionItemMapper（避开已审批处方并入副作用）。
 */
class MedicationLookupServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConversationService conversations = mock(ConversationService.class);
    private final HealthProfileMapper profileMapper = mock(HealthProfileMapper.class);
    private final HealthProfileAllergyMapper allergyMapper = mock(HealthProfileAllergyMapper.class);
    private final MedicationMapper medicationMapper = mock(MedicationMapper.class);
    private final ContraindicationFactRepository factRepository = mock(ContraindicationFactRepository.class);
    private final ContraindicationRuleEngine ruleEngine = new ContraindicationRuleEngine(TestContracts.instance());
    private final MedicationLookupService service = new MedicationLookupService(
            conversations,
            profileMapper,
            allergyMapper,
            medicationMapper,
            factRepository,
            ruleEngine,
            objectMapper,
            TestDisclaimers.instance(),
            TestContracts.instance());

    @Test
    void matchedMedicationProducesDualOutputCardsWithSafeDecision() {
        // ADR-0025 差异化点 3：一次查询产出 medication_info + medication_safety 两条独立消息
        Conversation conversation = conversation(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("查药品"))).thenReturn(conversation);
        Medication med = medication(1L, "阿莫西林胶囊", "阿莫西林");
        when(medicationMapper.selectActiveByNameOrGeneric("阿莫西林胶囊")).thenReturn(List.of(med));
        HealthProfile profile = profile(31L);
        when(profileMapper.selectActive(12L)).thenReturn(profile);
        when(allergyMapper.selectAllergens(31L)).thenReturn(List.of());
        when(factRepository.load(List.of(1L)))
                .thenReturn(new ContraindicationFacts(
                        List.of(new MedicationContraindicationFact(1L, List.of("阿莫西林"), List.of())), List.of(), true));

        MedicationLookupService.MedicationLookupView view =
                service.lookupAndAppend(12L, null, "查药品", List.of("阿莫西林胶囊"));

        assertThat(view.conversationId()).isEqualTo(7L);
        assertThat(view.notFound()).isFalse();
        // 说明书卡片承载 medications.instructions
        assertThat(view.medicationInfo().path("medications").get(0).path("name").asText())
                .isEqualTo("阿莫西林胶囊");
        assertThat(view.medicationInfo()
                        .path("medications")
                        .get(0)
                        .path("instructions")
                        .asText())
                .isEqualTo("适应症/用法用量/注意事项");
        // 安全结果：无过敏原 -> SAFE；档案过敏史为空同样视为"未提供"（票 16/46 同约定）
        assertThat(view.medicationSafety().path("decision").asText()).isEqualTo("SAFE");
        assertThat(view.medicationSafety().path("blocked").asBoolean()).isFalse();
        assertThat(view.medicationSafety().path("message").asText()).contains("无法完整确认");
        // 双出口两条消息按序回落
        verify(conversations)
                .appendMessage(
                        eq(7L),
                        eq("assistant"),
                        anyString(),
                        eq(Message.KIND_MEDICATION_INFO),
                        eq(null),
                        eq(null),
                        eq(null));
        verify(conversations)
                .appendMessage(
                        eq(7L),
                        eq("assistant"),
                        anyString(),
                        eq(Message.KIND_MEDICATION_SAFETY),
                        eq(null),
                        eq(null),
                        eq(null));
    }

    @Test
    void noMatchAppendsTextHintAndReturnsNotFoundWithoutRuleEngine() {
        // 未匹配任何药品：落 text 消息引导，不走规则引擎（不查 Neo4j）
        Conversation conversation = conversation(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("查药品"))).thenReturn(conversation);
        when(medicationMapper.selectActiveByNameOrGeneric("不存在的药")).thenReturn(List.of());
        when(medicationMapper.selectActiveByNameLike("%不存在的药%")).thenReturn(List.of());

        MedicationLookupService.MedicationLookupView view = service.lookupAndAppend(12L, null, "查药品", List.of("不存在的药"));

        assertThat(view.notFound()).isTrue();
        assertThat(view.medicationInfo()).isNull();
        assertThat(view.medicationSafety()).isNull();
        // 落一条 text 消息，不落 medication_info/medication_safety
        verify(conversations)
                .appendMessage(
                        eq(7L), eq("assistant"), anyString(), eq(Message.KIND_TEXT), eq(null), eq(null), eq(null));
        // 不查 Neo4j（规则引擎不触发）
        verify(factRepository, org.mockito.Mockito.never()).load(anyList());
    }

    @Test
    void genericNameColumnMatchWorksWhenBrandNameMisses() {
        // ADR-0025 差异化点 1：商品名查不到时通用名列也能命中
        Conversation conversation = conversation(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("查药品"))).thenReturn(conversation);
        Medication med = medication(2L, "芬必得", "布洛芬");
        when(medicationMapper.selectActiveByNameOrGeneric("布洛芬")).thenReturn(List.of(med));
        when(profileMapper.selectActive(12L)).thenReturn(profile(31L));
        when(allergyMapper.selectAllergens(31L)).thenReturn(List.of());
        when(factRepository.load(List.of(2L)))
                .thenReturn(new ContraindicationFacts(
                        List.of(new MedicationContraindicationFact(2L, List.of("布洛芬"), List.of())), List.of(), true));

        MedicationLookupService.MedicationLookupView view = service.lookupAndAppend(12L, null, "查药品", List.of("布洛芬"));

        assertThat(view.notFound()).isFalse();
        assertThat(view.medicationInfo()
                        .path("medications")
                        .get(0)
                        .path("generic_name")
                        .asText())
                .isEqualTo("布洛芬");
    }

    @Test
    void fuzzyLikeMatchKicksInWhenExactMatchMisses() {
        // vision OCR 不完全准确时，精确查无果再用 LIKE 模糊匹配兜底
        Conversation conversation = conversation(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍药盒"))).thenReturn(conversation);
        Medication med = medication(3L, "阿莫西林胶囊", "阿莫西林");
        when(medicationMapper.selectActiveByNameOrGeneric("阿莫西林")).thenReturn(List.of());
        when(medicationMapper.selectActiveByNameLike("%阿莫西林%")).thenReturn(List.of(med));
        when(profileMapper.selectActive(12L)).thenReturn(profile(31L));
        when(allergyMapper.selectAllergens(31L)).thenReturn(List.of());
        when(factRepository.load(List.of(3L)))
                .thenReturn(new ContraindicationFacts(
                        List.of(new MedicationContraindicationFact(3L, List.of("阿莫西林"), List.of())), List.of(), true));

        MedicationLookupService.MedicationLookupView view = service.lookupAndAppend(12L, null, "拍药盒", List.of("阿莫西林"));

        assertThat(view.notFound()).isFalse();
        verify(medicationMapper).selectActiveByNameLike("%阿莫西林%");
    }

    @Test
    void blockedWhenAllergenMatchesMedicationIngredient() {
        // 命中禁忌：过敏原"青霉素" × 阿莫西林成分 -> BLOCKED，安全卡片突出警告
        Conversation conversation = conversation(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("查药品"))).thenReturn(conversation);
        Medication med = medication(1L, "阿莫西林胶囊", "阿莫西林");
        when(medicationMapper.selectActiveByNameOrGeneric("阿莫西林胶囊")).thenReturn(List.of(med));
        when(profileMapper.selectActive(12L)).thenReturn(profile(31L));
        when(allergyMapper.selectAllergens(31L)).thenReturn(List.of("青霉素"));
        when(factRepository.load(List.of(1L)))
                .thenReturn(new ContraindicationFacts(
                        List.of(new MedicationContraindicationFact(1L, List.of("阿莫西林"), List.of("青霉素"))),
                        List.of(),
                        true));

        MedicationLookupService.MedicationLookupView view =
                service.lookupAndAppend(12L, null, "查药品", List.of("阿莫西林胶囊"));

        assertThat(view.medicationSafety().path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(view.medicationSafety().path("blocked").asBoolean()).isTrue();
        assertThat(view.medicationSafety().path("message").asText()).contains("禁忌");
        assertThat(view.medicationSafety().path("advice").asText()).contains("咨询医生或药师");
    }

    @Test
    void reviewRequiredWhenNeo4jFactsUnavailableIsFailClosed() {
        // Neo4j 不可用时 fail-closed 退化为 REVIEW_REQUIRED，与 ContraindicationService 同模式
        Conversation conversation = conversation(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("查药品"))).thenReturn(conversation);
        Medication med = medication(1L, "阿莫西林胶囊", "阿莫西林");
        when(medicationMapper.selectActiveByNameOrGeneric("阿莫西林胶囊")).thenReturn(List.of(med));
        when(profileMapper.selectActive(12L)).thenReturn(profile(31L));
        when(allergyMapper.selectAllergens(31L)).thenReturn(List.of());
        when(factRepository.load(List.of(1L))).thenThrow(new IllegalStateException("Neo4j unavailable"));

        MedicationLookupService.MedicationLookupView view =
                service.lookupAndAppend(12L, null, "查药品", List.of("阿莫西林胶囊"));

        assertThat(view.medicationSafety().path("decision").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(view.medicationSafety().path("blocked").asBoolean()).isTrue();
    }

    @Test
    void noHealthProfileDegradesToEmptyAllergiesButInfoCardStillProduced() {
        // 无档案优雅降级（空过敏列表）：说明书卡片仍可用，安全检查尽力而为（SAFE）
        Conversation conversation = conversation(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("查药品"))).thenReturn(conversation);
        Medication med = medication(1L, "阿莫西林胶囊", "阿莫西林");
        when(medicationMapper.selectActiveByNameOrGeneric("阿莫西林胶囊")).thenReturn(List.of(med));
        when(profileMapper.selectActive(12L)).thenReturn(null);
        when(factRepository.load(List.of(1L)))
                .thenReturn(new ContraindicationFacts(
                        List.of(new MedicationContraindicationFact(1L, List.of("阿莫西林"), List.of())), List.of(), true));

        MedicationLookupService.MedicationLookupView view =
                service.lookupAndAppend(12L, null, "查药品", List.of("阿莫西林胶囊"));

        // 说明书卡片仍产出
        assertThat(view.notFound()).isFalse();
        assertThat(view.medicationInfo().path("medications").get(0).path("name").asText())
                .isEqualTo("阿莫西林胶囊");
        // 无过敏原 -> SAFE（尽力而为）；空过敏史=未提供（票 46/票 16 同约定），文案不得声称已确认
        assertThat(view.medicationSafety().path("decision").asText()).isEqualTo("SAFE");
        assertThat(view.medicationSafety().path("message").asText()).contains("无法完整确认");
        // 不查过敏原（无档案）
        verify(allergyMapper, org.mockito.Mockito.never()).selectAllergens(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dualOutputCardsBothCarryDisclaimer() throws Exception {
        // 硬约束 1：两条卡片均挂通用免责
        Conversation conversation = conversation(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("查药品"))).thenReturn(conversation);
        Medication med = medication(1L, "阿莫西林胶囊", "阿莫西林");
        when(medicationMapper.selectActiveByNameOrGeneric("阿莫西林胶囊")).thenReturn(List.of(med));
        when(profileMapper.selectActive(12L)).thenReturn(null);
        when(factRepository.load(List.of(1L)))
                .thenReturn(new ContraindicationFacts(
                        List.of(new MedicationContraindicationFact(1L, List.of("阿莫西林"), List.of())), List.of(), true));

        service.lookupAndAppend(12L, null, "查药品", List.of("阿莫西林胶囊"));

        // 两条 appendMessage 的 content（卡片 JSON）都应含 disclaimer 字段
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conversations, org.mockito.Mockito.times(2))
                .appendMessage(
                        eq(7L),
                        eq("assistant"),
                        captor.capture(),
                        org.mockito.ArgumentMatchers.anyString(),
                        eq(null),
                        eq(null),
                        eq(null));
        for (String cardJson : captor.getAllValues()) {
            JsonNode card = objectMapper.readTree(cardJson);
            assertThat(card.path("disclaimer").asText()).isEqualTo("仅供参考，不替代医生诊断");
        }
    }

    private Conversation conversation(long id) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        return conversation;
    }

    private HealthProfile profile(long id) {
        HealthProfile profile = new HealthProfile();
        profile.setId(id);
        return profile;
    }

    private Medication medication(long id, String name, String genericName) {
        Medication medication = new Medication();
        medication.setId(id);
        medication.setName(name);
        medication.setGenericName(genericName);
        medication.setSpecification("0.25g");
        medication.setInstructions("适应症/用法用量/注意事项");
        medication.setIsActive(true);
        return medication;
    }
}

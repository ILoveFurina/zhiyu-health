package com.zhiyu.health.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiyu.health.support.TestContracts;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContraindicationRuleEngineTest {

    private final ContraindicationRuleEngine engine = new ContraindicationRuleEngine(TestContracts.instance());

    @Test
    void blocksWhenAnAllergyMatchesAMedicationIngredient() {
        ContraindicationFacts facts = new ContraindicationFacts(
                List.of(new MedicationContraindicationFact(1L, List.of("阿莫西林", "青霉素"), List.of("青霉素"))),
                List.of(),
                true);

        ContraindicationResult result = engine.judge(List.of("青霉素"), List.of(1L), facts);

        assertThat(result.decision()).isEqualTo("BLOCKED");
        assertThat(result.blocked()).isTrue();
        assertThat(result.reasons()).containsExactly("过敏史“青霉素”与药品 1 的成分/禁忌项匹配");
    }

    @Test
    void allowsWhenAllergiesDoNotMatchIngredientsOrContraindications() {
        ContraindicationFacts facts = new ContraindicationFacts(
                List.of(new MedicationContraindicationFact(2L, List.of("布洛芬"), List.of("阿司匹林"))), List.of(), true);

        ContraindicationResult result = engine.judge(List.of("青霉素"), List.of(2L), facts);

        assertThat(result.decision()).isEqualTo("SAFE");
        assertThat(result.blocked()).isFalse();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void blocksWhenCandidateMedicationsInteract() {
        ContraindicationFacts facts = new ContraindicationFacts(
                List.of(
                        new MedicationContraindicationFact(2L, List.of("布洛芬"), List.of()),
                        new MedicationContraindicationFact(3L, List.of("氯雷他定"), List.of())),
                List.of(new MedicationInteractionFact(2L, 3L, "合用可能增加不良反应风险")),
                true);

        ContraindicationResult result = engine.judge(List.of(), List.of(2L, 3L), facts);

        assertThat(result.decision()).isEqualTo("BLOCKED");
        assertThat(result.blocked()).isTrue();
        assertThat(result.reasons()).containsExactly("药品 2 与药品 3：合用可能增加不良反应风险");
    }

    @Test
    void requiresHumanReviewWhenFactsAreMissing() {
        ContraindicationFacts facts = new ContraindicationFacts(
                List.of(new MedicationContraindicationFact(1L, List.of("阿莫西林"), List.of("青霉素"))), List.of(), false);

        ContraindicationResult result = engine.judge(List.of(), List.of(1L, 2L), facts);

        assertThat(result.decision()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.blocked()).isTrue();
        assertThat(result.reasons()).containsExactly("禁忌知识数据不完整，无法确认候选药品安全性");
    }
}

package com.zhiyu.health.rule;

import com.zhiyu.health.config.Contracts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 过敏史与候选药品的确定性安全门；LLM 不参与也不能覆盖判定。 */
@Component
@RequiredArgsConstructor
public class ContraindicationRuleEngine {

    private final Contracts contracts;

    public ContraindicationResult judge(
            List<String> allergies, List<Long> candidateMedicationIds, ContraindicationFacts facts) {
        Contracts.Contraindication contract = contracts.contraindication();
        Set<Long> factMedicationIds = new HashSet<>();
        facts.medications().forEach(fact -> factMedicationIds.add(fact.medicationId()));
        if (!facts.complete() || !factMedicationIds.containsAll(candidateMedicationIds)) {
            return result(contract, "review_required", true, List.of("禁忌知识数据不完整，无法确认候选药品安全性"));
        }

        List<String> reasons = new ArrayList<>();
        Set<Long> candidates = Set.copyOf(candidateMedicationIds);
        for (MedicationContraindicationFact medication : facts.medications()) {
            if (!candidates.contains(medication.medicationId())) {
                continue;
            }
            List<String> medicationTerms = new ArrayList<>(medication.ingredients());
            medicationTerms.addAll(medication.allergyTerms());
            for (String allergy : allergies) {
                if (medicationTerms.stream().anyMatch(term -> matches(allergy, term))) {
                    reasons.add("过敏史“%s”与药品 %d 的成分/禁忌项匹配".formatted(allergy.trim(), medication.medicationId()));
                }
            }
        }
        for (MedicationInteractionFact interaction : facts.interactions()) {
            if (candidates.contains(interaction.leftMedicationId())
                    && candidates.contains(interaction.rightMedicationId())) {
                reasons.add("药品 %d 与药品 %d：%s"
                        .formatted(
                                interaction.leftMedicationId(), interaction.rightMedicationId(), interaction.reason()));
            }
        }
        return reasons.isEmpty()
                ? result(contract, "safe", false, List.of())
                : result(contract, "blocked", true, reasons);
    }

    private ContraindicationResult result(
            Contracts.Contraindication contract, String decisionKey, boolean blocked, List<String> reasons) {
        String messageType = contract.messageTypes().get(blocked ? "warning" : "result");
        return new ContraindicationResult(
                contract.decisions().get(decisionKey),
                messageType,
                blocked,
                reasons,
                contract.messages().get(decisionKey),
                blocked ? contract.advice() : null);
    }

    private boolean matches(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        return !normalizedLeft.isEmpty()
                && !normalizedRight.isEmpty()
                && (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}

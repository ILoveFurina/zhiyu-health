package com.zhiyu.health.rule;

import com.zhiyu.health.config.Contracts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 过敏史与候选药品的确定性安全门；LLM 不参与也不能覆盖判定。 */
@Component
@RequiredArgsConstructor
public class ContraindicationRuleEngine {

    private final Contracts contracts;

    public ContraindicationResult judge(
            List<String> allergies,
            List<Long> candidateMedicationIds,
            ContraindicationFacts facts,
            Map<Long, String> medicationNames) {
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
                    // B 端开方场景：reason 只展示 PG 权威药名，不暴露裸 id（票 93）。
                    reasons.add("该患者过敏史“%s”与%s的成分/禁忌项匹配"
                            .formatted(allergy.trim(), nameOf(medication.medicationId(), medicationNames)));
                }
            }
        }
        for (MedicationInteractionFact interaction : facts.interactions()) {
            boolean involvesCandidate = candidates.contains(interaction.leftMedicationId())
                    || candidates.contains(interaction.rightMedicationId());
            if (involvesCandidate
                    && factMedicationIds.contains(interaction.leftMedicationId())
                    && factMedicationIds.contains(interaction.rightMedicationId())) {
                reasons.add("%s 与 %s：%s"
                        .formatted(
                                nameOf(interaction.leftMedicationId(), medicationNames),
                                nameOf(interaction.rightMedicationId(), medicationNames),
                                interaction.reason()));
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

    private String nameOf(long medicationId, Map<Long, String> medicationNames) {
        // PG medications.name 是业务权威药名；参与判定的 id 均来自已校验候选或档案在用药，
        // 名称必然存在，此处兜底仅防御未来数据漂移，避免把裸 id 暴露给医生。
        return medicationNames.getOrDefault(medicationId, "未知药品");
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

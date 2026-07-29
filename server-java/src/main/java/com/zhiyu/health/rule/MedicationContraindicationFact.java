package com.zhiyu.health.rule;

import java.util.List;

/** Neo4j 中单个药品的只读禁忌事实；名称快照不进入业务响应。 */
public record MedicationContraindicationFact(long medicationId, List<String> ingredients, List<String> allergyTerms) {
    public MedicationContraindicationFact {
        ingredients = List.copyOf(ingredients);
        allergyTerms = List.copyOf(allergyTerms);
    }
}

package com.zhiyu.health.rule;

import java.util.List;

/** 一次候选药集合所需的完整 Neo4j 事实快照。 */
public record ContraindicationFacts(
        List<MedicationContraindicationFact> medications,
        List<MedicationInteractionFact> interactions,
        boolean complete) {
    public ContraindicationFacts {
        medications = List.copyOf(medications);
        interactions = List.copyOf(interactions);
    }
}

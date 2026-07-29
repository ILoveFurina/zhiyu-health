package com.zhiyu.health.rule;

/** Neo4j 中两个药品之间的确定性相互作用事实。 */
public record MedicationInteractionFact(long leftMedicationId, long rightMedicationId, String reason) {}

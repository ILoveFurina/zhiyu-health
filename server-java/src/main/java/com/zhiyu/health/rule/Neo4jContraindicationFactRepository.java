package com.zhiyu.health.rule;

import static org.neo4j.driver.Values.parameters;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.stereotype.Repository;

/** Neo4j 禁忌子图的只读适配器；Cypher 固定为 READ session，不承担规则判断。 */
@Repository
@RequiredArgsConstructor
public class Neo4jContraindicationFactRepository implements ContraindicationFactRepository {

    private static final String MEDICATION_FACTS =
            """
            UNWIND $medicationIds AS medicationId
            OPTIONAL MATCH (m:Medication {medication_id: medicationId})
            OPTIONAL MATCH (m)-[:CONTRAINDICATED_FOR]->(c:Contraindication)
            RETURN medicationId, m IS NOT NULL AS found,
                   coalesce(m.ingredients, []) AS ingredients,
                   [allergen IN collect(DISTINCT c.allergen) WHERE allergen IS NOT NULL] AS allergyTerms
            ORDER BY medicationId
            """;

    private static final String INTERACTIONS =
            """
            MATCH (left:Medication)-[interaction:INTERACTS_WITH]-(right:Medication)
            WHERE left.medication_id IN $medicationIds
              AND right.medication_id IN $medicationIds
              AND left.medication_id < right.medication_id
            RETURN DISTINCT left.medication_id AS leftId,
                   right.medication_id AS rightId,
                   interaction.reason AS reason
            ORDER BY leftId, rightId
            """;

    private final Driver driver;

    @Override
    public ContraindicationFacts load(List<Long> medicationIds) {
        SessionConfig readOnly =
                SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build();
        try (Session session = driver.session(readOnly)) {
            List<Record> medicationRecords =
                    session.executeRead(tx -> tx.run(MEDICATION_FACTS, parameters("medicationIds", medicationIds))
                            .list());
            List<MedicationContraindicationFact> medications = medicationRecords.stream()
                    .filter(record -> record.get("found").asBoolean())
                    .map(record -> new MedicationContraindicationFact(
                            record.get("medicationId").asLong(),
                            record.get("ingredients").asList(value -> value.asString()),
                            record.get("allergyTerms").asList(value -> value.asString())))
                    .toList();
            List<MedicationInteractionFact> interactions =
                    session.executeRead(tx -> tx.run(INTERACTIONS, parameters("medicationIds", medicationIds))
                            .list(record -> toInteraction(record)));
            boolean complete = medications.size() == medicationIds.size()
                    && medications.stream()
                            .allMatch(medication -> !medication.ingredients().isEmpty());
            return new ContraindicationFacts(medications, interactions, complete);
        }
    }

    private MedicationInteractionFact toInteraction(Record record) {
        return new MedicationInteractionFact(
                record.get("leftId").asLong(),
                record.get("rightId").asLong(),
                record.get("reason").asString());
    }
}

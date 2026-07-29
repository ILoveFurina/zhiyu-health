// 智愈医学知识图谱——禁忌子图幂等 seed。
// 人工在 Neo4j 维护窗口执行；应用启动与测试不会自动改写云端图谱。
CREATE CONSTRAINT medication_id_unique IF NOT EXISTS
FOR (medication:Medication) REQUIRE medication.medication_id IS UNIQUE;

CREATE CONSTRAINT contraindication_key_unique IF NOT EXISTS
FOR (contraindication:Contraindication) REQUIRE contraindication.key IS UNIQUE;

MERGE (amoxicillin:Medication {medication_id: 1})
SET amoxicillin.name_snapshot = '阿莫西林胶囊',
    amoxicillin.ingredients = ['阿莫西林', '青霉素类'];
MERGE (ibuprofen:Medication {medication_id: 2})
SET ibuprofen.name_snapshot = '布洛芬缓释胶囊',
    ibuprofen.ingredients = ['布洛芬'];
MERGE (loratadine:Medication {medication_id: 3})
SET loratadine.name_snapshot = '氯雷他定片',
    loratadine.ingredients = ['氯雷他定'];
MERGE (warfarin:Medication {medication_id: 4})
SET warfarin.name_snapshot = '华法林钠片',
    warfarin.ingredients = ['华法林钠', '华法林', '香豆素类'];

MERGE (penicillinAllergy:Contraindication {key: 'allergy:penicillin'})
SET penicillinAllergy.name = '青霉素类过敏', penicillinAllergy.allergen = '青霉素';
MERGE (nsaidAllergy:Contraindication {key: 'allergy:nsaid'})
SET nsaidAllergy.name = '非甾体抗炎药过敏', nsaidAllergy.allergen = '非甾体抗炎药';
MERGE (coumarinAllergy:Contraindication {key: 'allergy:coumarin'})
SET coumarinAllergy.name = '香豆素类过敏', coumarinAllergy.allergen = '香豆素';

MERGE (amoxicillin)-[amoxicillinRisk:CONTRAINDICATED_FOR]->(penicillinAllergy)
SET amoxicillinRisk.reason = '阿莫西林属于青霉素类，青霉素过敏者禁用';
MERGE (ibuprofen)-[ibuprofenRisk:CONTRAINDICATED_FOR]->(nsaidAllergy)
SET ibuprofenRisk.reason = '非甾体抗炎药过敏者应避免使用布洛芬';
MERGE (warfarin)-[warfarinRisk:CONTRAINDICATED_FOR]->(coumarinAllergy)
SET warfarinRisk.reason = '香豆素类过敏者不应使用华法林';

MERGE (ibuprofen)-[bleedingRisk:INTERACTS_WITH]->(warfarin)
SET bleedingRisk.severity = 'HIGH',
    bleedingRisk.reason = '合用可能增加胃肠道及其他出血风险';

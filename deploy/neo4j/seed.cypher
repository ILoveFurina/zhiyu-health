// 智愈医学知识图谱——禁忌子图幂等 seed。
// 人工在 Neo4j 维护窗口执行；应用启动与测试不会自动改写云端图谱。
CREATE CONSTRAINT medication_id_unique IF NOT EXISTS
FOR (medication:Medication) REQUIRE medication.medication_id IS UNIQUE;

CREATE CONSTRAINT contraindication_key_unique IF NOT EXISTS
FOR (contraindication:Contraindication) REQUIRE contraindication.key IS UNIQUE;

UNWIND [
  {id: 1, name: '阿莫西林胶囊', ingredients: ['阿莫西林', '青霉素类']},
  {id: 2, name: '布洛芬缓释胶囊', ingredients: ['布洛芬', '非甾体抗炎药']},
  {id: 3, name: '氯雷他定片', ingredients: ['氯雷他定']},
  {id: 4, name: '华法林钠片', ingredients: ['华法林钠', '华法林', '香豆素类']},
  {id: 5, name: '对乙酰氨基酚片', ingredients: ['对乙酰氨基酚']},
  {id: 6, name: '头孢克洛胶囊', ingredients: ['头孢克洛', '头孢菌素类']},
  {id: 7, name: '阿奇霉素片', ingredients: ['阿奇霉素', '大环内酯类']},
  {id: 8, name: '左氧氟沙星片', ingredients: ['左氧氟沙星', '喹诺酮类']},
  {id: 9, name: '奥美拉唑肠溶胶囊', ingredients: ['奥美拉唑']},
  {id: 10, name: '二甲双胍片', ingredients: ['二甲双胍']},
  {id: 11, name: '阿卡波糖片', ingredients: ['阿卡波糖']},
  {id: 12, name: '氨氯地平片', ingredients: ['苯磺酸氨氯地平', '氨氯地平']},
  {id: 13, name: '厄贝沙坦片', ingredients: ['厄贝沙坦']},
  {id: 14, name: '美托洛尔缓释片', ingredients: ['琥珀酸美托洛尔', '美托洛尔']},
  {id: 15, name: '阿托伐他汀钙片', ingredients: ['阿托伐他汀钙', '阿托伐他汀']},
  {id: 16, name: '瑞舒伐他汀钙片', ingredients: ['瑞舒伐他汀钙', '瑞舒伐他汀']},
  {id: 17, name: '阿司匹林肠溶片', ingredients: ['阿司匹林', '水杨酸类']},
  {id: 18, name: '氯吡格雷片', ingredients: ['硫酸氢氯吡格雷', '氯吡格雷']},
  {id: 19, name: '蒙脱石散', ingredients: ['蒙脱石']},
  {id: 20, name: '多潘立酮片', ingredients: ['多潘立酮']},
  {id: 21, name: '西替利嗪片', ingredients: ['盐酸西替利嗪', '西替利嗪']},
  {id: 22, name: '孟鲁司特钠片', ingredients: ['孟鲁司特钠', '孟鲁司特']},
  {id: 23, name: '沙丁胺醇吸入气雾剂', ingredients: ['硫酸沙丁胺醇', '沙丁胺醇']},
  {id: 24, name: '布地奈德吸入粉雾剂', ingredients: ['布地奈德']},
  {id: 25, name: '左甲状腺素钠片', ingredients: ['左甲状腺素钠', '左甲状腺素']},
  {id: 26, name: '甲巯咪唑片', ingredients: ['甲巯咪唑']},
  {id: 27, name: '碳酸钙D3片', ingredients: ['碳酸钙', '维生素D3']},
  {id: 28, name: '维生素B2片', ingredients: ['核黄素', '维生素B2']},
  {id: 29, name: '甲硝唑片', ingredients: ['甲硝唑', '硝基咪唑类']},
  {id: 30, name: '呋喃妥因肠溶片', ingredients: ['呋喃妥因', '硝基呋喃类']}
] AS data
MERGE (medication:Medication {medication_id: data.id})
SET medication.name_snapshot = data.name, medication.ingredients = data.ingredients;

UNWIND [
  {key: 'allergy:penicillin', name: '青霉素类过敏', allergen: '青霉素'},
  {key: 'allergy:nsaid', name: '非甾体抗炎药过敏', allergen: '非甾体抗炎药'},
  {key: 'allergy:coumarin', name: '香豆素类过敏', allergen: '香豆素'},
  {key: 'allergy:cephalosporin', name: '头孢菌素类过敏', allergen: '头孢菌素'},
  {key: 'allergy:macrolide', name: '大环内酯类过敏', allergen: '大环内酯'},
  {key: 'allergy:quinolone', name: '喹诺酮类过敏', allergen: '喹诺酮'},
  {key: 'allergy:salicylate', name: '水杨酸类过敏', allergen: '水杨酸'},
  {key: 'allergy:nitroimidazole', name: '硝基咪唑类过敏', allergen: '硝基咪唑'},
  {key: 'allergy:nitrofuran', name: '硝基呋喃类过敏', allergen: '硝基呋喃'}
] AS data
MERGE (contraindication:Contraindication {key: data.key})
SET contraindication.name = data.name, contraindication.allergen = data.allergen;

UNWIND [
  {medication_id: 1, key: 'allergy:penicillin', reason: '阿莫西林属于青霉素类，青霉素过敏者禁用'},
  {medication_id: 2, key: 'allergy:nsaid', reason: '非甾体抗炎药过敏者应避免使用布洛芬'},
  {medication_id: 4, key: 'allergy:coumarin', reason: '香豆素类过敏者不应使用华法林'},
  {medication_id: 6, key: 'allergy:cephalosporin', reason: '头孢克洛属于头孢菌素类，相关过敏者禁用'},
  {medication_id: 7, key: 'allergy:macrolide', reason: '阿奇霉素属于大环内酯类，相关过敏者禁用'},
  {medication_id: 8, key: 'allergy:quinolone', reason: '左氧氟沙星属于喹诺酮类，相关过敏者禁用'},
  {medication_id: 17, key: 'allergy:salicylate', reason: '阿司匹林属于水杨酸类，相关过敏者禁用'},
  {medication_id: 29, key: 'allergy:nitroimidazole', reason: '甲硝唑属于硝基咪唑类，相关过敏者禁用'},
  {medication_id: 30, key: 'allergy:nitrofuran', reason: '呋喃妥因属于硝基呋喃类，相关过敏者禁用'}
] AS data
MATCH (medication:Medication {medication_id: data.medication_id})
MATCH (contraindication:Contraindication {key: data.key})
MERGE (medication)-[risk:CONTRAINDICATED_FOR]->(contraindication)
SET risk.reason = data.reason;

UNWIND [
  {left: 2, right: 4, severity: 'HIGH', reason: '合用可能增加胃肠道及其他出血风险'},
  {left: 17, right: 4, severity: 'HIGH', reason: '合用可能显著增加出血风险'},
  {left: 17, right: 2, severity: 'HIGH', reason: '合用可能增加胃肠道不良反应和出血风险'},
  {left: 9, right: 18, severity: 'MODERATE', reason: '奥美拉唑可能降低氯吡格雷的抗血小板作用'},
  {left: 7, right: 20, severity: 'HIGH', reason: '合用可能增加 QT 间期延长和心律失常风险'},
  {left: 27, right: 25, severity: 'MODERATE', reason: '碳酸钙可能降低左甲状腺素吸收，需错开服用'},
  {left: 29, right: 4, severity: 'HIGH', reason: '甲硝唑可能增强华法林作用并增加出血风险'},
  {left: 8, right: 4, severity: 'HIGH', reason: '左氧氟沙星可能增强华法林作用并增加出血风险'}
] AS data
MATCH (left:Medication {medication_id: data.left})
MATCH (right:Medication {medication_id: data.right})
MERGE (left)-[interaction:INTERACTS_WITH]->(right)
SET interaction.severity = data.severity, interaction.reason = data.reason;

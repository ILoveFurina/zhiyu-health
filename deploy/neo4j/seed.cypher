// 智愈医学知识图谱--全量幂等 seed（ADR-0006 / 票 13）。
//
// 五类节点：症状(Symptom) / 疾病(Disease) / 科室(Department) / 药品(Medication) / 禁忌(Contraindication)。
// 人工在 Neo4j 维护窗口执行；应用启动与测试不会自动改写云端图谱（ADR-0006 只读边界）。
// seed 与知识库场景同源：症状对齐 knowledge_chunks.title，科室对齐 knowledge_chunks.department，
// 药品对齐 seed.sql medications（medication_id 作为图谱与业务库的唯一 join key）。
//
// node_id 采用 {label_type}:{natural_key} 复合形式（ADR-0013 决策 6），
// 投影接口统一按 node_id 返回，前端按 group(label) 着色五类节点。

// ========== 唯一性约束 ==========
CREATE CONSTRAINT medication_id_unique IF NOT EXISTS
FOR (medication:Medication) REQUIRE medication.medication_id IS UNIQUE;

CREATE CONSTRAINT contraindication_key_unique IF NOT EXISTS
FOR (contraindication:Contraindication) REQUIRE contraindication.key IS UNIQUE;

CREATE CONSTRAINT symptom_name_unique IF NOT EXISTS
FOR (symptom:Symptom) REQUIRE symptom.name IS UNIQUE;

CREATE CONSTRAINT department_name_unique IF NOT EXISTS
FOR (department:Department) REQUIRE department.name IS UNIQUE;

CREATE CONSTRAINT disease_name_unique IF NOT EXISTS
FOR (disease:Disease) REQUIRE disease.name IS UNIQUE;

// ========== 药品节点（对齐 seed.sql medications id 1-30）==========
// ADR-0006：Neo4j 药品节点只保留 medication_id 与名称快照 + 成分，作为图谱 join key；
// 业务库 PG medications 是药品权威数据源，图谱不双写业务字段。
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
SET medication.name_snapshot = data.name,
    medication.ingredients = data.ingredients,
    medication.node_id = 'medication:' + toString(data.id);

// ========== 禁忌节点（9 类过敏原）==========
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
SET contraindication.name = data.name,
    contraindication.allergen = data.allergen,
    contraindication.node_id = 'contraindication:' + data.key;

// ========== 科室节点（对齐 knowledge_chunks.department，共 10 个）==========
// 科室是导诊推荐的目标，症状与疾病均挂接到科室。
UNWIND [
  {name: '心血管内科', description: '诊治心脏及血管疾病，如高血压、冠心病、心律失常等'},
  {name: '呼吸内科', description: '诊治呼吸系统疾病，如哮喘、支气管炎、肺炎等'},
  {name: '消化内科', description: '诊治消化系统疾病，如胃炎、溃疡、反流等'},
  {name: '神经内科', description: '诊治神经系统疾病，如头痛、眩晕、面瘫等'},
  {name: '内分泌科', description: '诊治内分泌代谢疾病，如糖尿病、甲状腺疾病等'},
  {name: '皮肤科', description: '诊治皮肤及附属器疾病，如湿疹、荨麻疹、痤疮等'},
  {name: '骨科', description: '诊治骨骼关节及软组织疾病，如骨折、关节炎、颈椎病等'},
  {name: '眼科', description: '诊治眼部疾病，如近视、青光眼、结膜炎等'},
  {name: '儿科', description: '诊治 14 岁以下儿童常见疾病'},
  {name: '妇科', description: '诊治女性生殖系统疾病，如月经异常、炎症等'}
] AS data
MERGE (department:Department {name: data.name})
SET department.description = data.description,
    department.node_id = 'department:' + data.name;

// ========== 症状节点（对齐 knowledge_chunks.title，共 50 个）==========
// aliases 用于 traverse_graph 实体名模糊对齐（grilling 决策 3）：
// Cypher 匹配 WHERE s.name = $entity OR $entity IN s.aliases，把"叫法不同"在 seed 层消解。
UNWIND [
  {name: '胸闷气短', department: '心血管内科', aliases: ['胸闷', '气短', '胸闷气促']},
  {name: '心悸心跳快', department: '心血管内科', aliases: ['心悸', '心跳快', '心慌']},
  {name: '血压偏高', department: '心血管内科', aliases: ['血压高', '高血压']},
  {name: '胸痛伴冷汗', department: '心血管内科', aliases: ['胸痛', '胸痛冷汗']},
  {name: '下肢水肿', department: '心血管内科', aliases: ['腿肿', '脚肿', '水肿']},
  {name: '咳嗽', department: '呼吸内科', aliases: ['咳', '咳嗽']},
  {name: '咳痰带血', department: '呼吸内科', aliases: ['咯血', '痰中带血']},
  {name: '气喘呼吸困难', department: '呼吸内科', aliases: ['气喘', '呼吸困难', '喘不上气']},
  {name: '长期咳痰', department: '呼吸内科', aliases: ['慢咳', '长期咳痰']},
  {name: '反复感冒', department: '呼吸内科', aliases: ['易感冒', '频繁感冒']},
  {name: '胃痛', department: '消化内科', aliases: ['胃疼', '上腹痛']},
  {name: '反酸烧心', department: '消化内科', aliases: ['反酸', '烧心', '胃酸']},
  {name: '腹泻', department: '消化内科', aliases: ['拉肚子', '拉稀']},
  {name: '便秘', department: '消化内科', aliases: ['排便困难']},
  {name: '腹胀', department: '消化内科', aliases: ['肚子胀', '胀气']},
  {name: '头痛', department: '神经内科', aliases: ['头疼']},
  {name: '头晕', department: '神经内科', aliases: ['眩晕', '头昏']},
  {name: '失眠', department: '神经内科', aliases: ['睡不着', '睡眠差']},
  {name: '手麻', department: '神经内科', aliases: ['手发麻', '手指麻木']},
  {name: '面瘫', department: '神经内科', aliases: ['面神经麻痹', '口角歪斜']},
  {name: '口渴多饮', department: '内分泌科', aliases: ['口干', '口渴']},
  {name: '多尿', department: '内分泌科', aliases: ['尿多', '夜尿多']},
  {name: '体重下降', department: '内分泌科', aliases: ['消瘦', '变瘦']},
  {name: '脖子增粗', department: '内分泌科', aliases: ['脖子粗', '颈部增粗']},
  {name: '怕热多汗', department: '内分泌科', aliases: ['多汗', '怕热']},
  {name: '湿疹', department: '皮肤科', aliases: ['湿疮']},
  {name: '荨麻疹', department: '皮肤科', aliases: ['风团', '风疹块']},
  {name: '痤疮', department: '皮肤科', aliases: ['青春痘', '粉刺']},
  {name: '皮肤瘙痒', department: '皮肤科', aliases: ['瘙痒', '身上痒']},
  {name: '皮肤起疹', department: '皮肤科', aliases: ['起疹子', '皮疹']},
  {name: '腰痛', department: '骨科', aliases: ['腰疼', '腰部疼痛']},
  {name: '关节痛', department: '骨科', aliases: ['关节疼痛', '关节疼']},
  {name: '颈肩痛', department: '骨科', aliases: ['颈肩疼痛', '脖子痛']},
  {name: '扭伤', department: '骨科', aliases: ['崴脚', '关节扭伤']},
  {name: '关节僵硬', department: '骨科', aliases: ['关节发僵', '晨僵']},
  {name: '视力下降', department: '眼科', aliases: ['视力减退', '看不清']},
  {name: '眼睛干涩', department: '眼科', aliases: ['眼干', '干眼']},
  {name: '眼红', department: '眼科', aliases: ['眼白发红', '眼睛红']},
  {name: '眼胀痛', department: '眼科', aliases: ['眼胀', '眼睛胀痛']},
  {name: '眼前黑影', department: '眼科', aliases: ['飞蚊', '眼前有黑影']},
  {name: '小儿发热', department: '儿科', aliases: ['儿童发烧', '小孩发烧']},
  {name: '小儿咳嗽', department: '儿科', aliases: ['儿童咳嗽', '小孩咳嗽']},
  {name: '小儿腹泻', department: '儿科', aliases: ['儿童拉肚子', '小孩拉肚子']},
  {name: '小儿皮疹', department: '儿科', aliases: ['儿童皮疹', '小孩起疹']},
  {name: '小儿厌食', department: '儿科', aliases: ['儿童厌食', '小孩不爱吃']},
  {name: '月经不调', department: '妇科', aliases: ['月经紊乱', '经期不准']},
  {name: '痛经', department: '妇科', aliases: ['月经痛']},
  {name: '白带异常', department: '妇科', aliases: ['白带多', '白带有异味']},
  {name: '经期延长', department: '妇科', aliases: ['月经时间长', '经期长']},
  {name: '下腹隐痛', department: '妇科', aliases: ['小腹痛', '下腹痛']}
] AS data
MERGE (symptom:Symptom {name: data.name})
SET symptom.aliases = data.aliases,
    symptom.department = data.department,
    symptom.node_id = 'symptom:' + data.name;

// ========== 疾病节点（共 57 个，规范名 + aliases + 所属科室 + 简述）==========
// 疾病是"症状->疾病->科室"三元链的中间节点（grilling 决策 7），补足图谱节点与边数量。
// 数据为常见疾病规范名，LLM 辅助生成 + 人工校对，校对列入待人工验收项。
UNWIND [
  {name: '心律失常', department: '心血管内科', aliases: ['心律不齐'], description: '心跳节律异常，包括过速、过缓或不规则'},
  {name: '冠心病', department: '心血管内科', aliases: ['冠状动脉粥样硬化性心脏病', '冠状动脉心脏病'], description: '冠状动脉狭窄导致心肌缺血的心脏病'},
  {name: '窦性心动过速', department: '心血管内科', aliases: ['心动过速'], description: '窦房结发放冲动频率超过 100 次/分'},
  {name: '原发性高血压', department: '心血管内科', aliases: ['高血压病', '高血压'], description: '病因不明的体循环动脉压持续升高'},
  {name: '急性冠脉综合征', department: '心血管内科', aliases: ['急性冠状动脉综合征'], description: '冠脉粥样斑块破裂导致急性心肌缺血综合征'},
  {name: '心功能不全', department: '心血管内科', aliases: ['心力衰竭', '心衰'], description: '心脏泵血功能下降导致循环障碍'},
  {name: '急性支气管炎', department: '呼吸内科', aliases: ['支气管炎'], description: '支气管黏膜急性炎症，多由感染引起'},
  {name: '上呼吸道感染', department: '呼吸内科', aliases: ['感冒', '上感'], description: '鼻咽喉部急性炎症，多为病毒感染'},
  {name: '肺结核', department: '呼吸内科', aliases: ['肺痨'], description: '结核分枝杆菌引起的肺部慢性传染病'},
  {name: '支气管扩张', department: '呼吸内科', aliases: ['支扩'], description: '支气管壁破坏导致不可逆扩张'},
  {name: '支气管哮喘', department: '呼吸内科', aliases: ['哮喘'], description: '气道慢性炎症致反复发作的可逆性气流受限'},
  {name: '慢阻肺', department: '呼吸内科', aliases: ['慢性阻塞性肺疾病', 'COPD'], description: '持续气流受限为特征的进展性肺疾病'},
  {name: '慢性支气管炎', department: '呼吸内科', aliases: ['老慢支'], description: '连续两年以上每年咳嗽咳痰三个月以上'},
  {name: '免疫力低下', department: '呼吸内科', aliases: ['免疫功能低下'], description: '免疫系统防御能力下降，易反复感染'},
  {name: '消化性溃疡', department: '消化内科', aliases: ['胃溃疡', '十二指肠溃疡'], description: '胃肠黏膜被胃酸消化形成的溃疡'},
  {name: '慢性胃炎', department: '消化内科', aliases: ['胃炎'], description: '胃黏膜慢性炎症，常见上腹不适'},
  {name: '胃食管反流病', department: '消化内科', aliases: ['反流性食管炎', 'GERD'], description: '胃内容物反流入食管引起症状或黏膜损伤'},
  {name: '急性肠炎', department: '消化内科', aliases: ['肠炎'], description: '肠道急性炎症，表现为腹泻腹痛'},
  {name: '功能性便秘', department: '消化内科', aliases: ['便秘'], description: '排除器质性病变的慢性排便困难'},
  {name: '功能性消化不良', department: '消化内科', aliases: ['消化不良'], description: '无器质性病变的上消化道症状群'},
  {name: '紧张性头痛', department: '神经内科', aliases: ['肌紧张性头痛'], description: '颈肩肌肉紧张引起的双侧压迫样头痛'},
  {name: '偏头痛', department: '神经内科', aliases: ['血管性头痛'], description: '反复发作的中重度搏动样头痛'},
  {name: '良性阵发性位置性眩晕', department: '神经内科', aliases: ['耳石症', 'BPPV'], description: '头位变化诱发的短暂旋转性眩晕'},
  {name: '原发性失眠', department: '神经内科', aliases: ['失眠症'], description: '排除其他病因的持续入睡或睡眠维持困难'},
  {name: '腕管综合征', department: '神经内科', aliases: ['鼠标手'], description: '正中神经在腕管受压致手麻无力'},
  {name: '面神经炎', department: '神经内科', aliases: ['面瘫', '贝尔麻痹'], description: '面神经非特异性炎症致面肌瘫痪'},
  {name: '糖尿病', department: '内分泌科', aliases: ['高血糖'], description: '胰岛素分泌或作用缺陷致慢性高血糖'},
  {name: '尿崩症', department: '内分泌科', aliases: [], description: '抗利尿激素缺乏致多尿多饮'},
  {name: '甲状腺肿大', department: '内分泌科', aliases: ['甲状腺肿', '大脖子病'], description: '甲状腺体积增大，可伴功能异常'},
  {name: '甲状腺功能亢进', department: '内分泌科', aliases: ['甲亢'], description: '甲状腺激素分泌过多致代谢亢进'},
  {name: '湿疹', department: '皮肤科', aliases: ['特应性皮炎'], description: '反复发作的炎症性皮肤病，伴瘙痒'},
  {name: '荨麻疹', department: '皮肤科', aliases: ['风团'], description: '皮肤黏膜血管扩张致局限性水肿风团'},
  {name: '痤疮', department: '皮肤科', aliases: ['青春痘', '粉刺'], description: '毛囊皮脂腺慢性炎症性皮肤病'},
  {name: '皮肤瘙痒症', department: '皮肤科', aliases: ['瘙痒症'], description: '无原发皮损的瘙痒性皮肤病'},
  {name: '接触性皮炎', department: '皮肤科', aliases: ['过敏性皮炎'], description: '皮肤接触外界物质后发生的炎症反应'},
  {name: '腰椎间盘突出', department: '骨科', aliases: ['腰突', '椎间盘突出'], description: '髓核突出压迫神经根致腰腿痛'},
  {name: '腰肌劳损', department: '骨科', aliases: ['腰肌炎'], description: '腰部肌肉及其附着点慢性损伤'},
  {name: '骨关节炎', department: '骨科', aliases: ['退行性关节炎', '关节炎'], description: '关节软骨退变伴骨质增生'},
  {name: '软组织损伤', department: '骨科', aliases: ['扭伤', '挫伤'], description: '皮肤以下骨骼以外的组织损伤'},
  {name: '类风湿关节炎', department: '骨科', aliases: ['类风湿', 'RA'], description: '对称性多关节慢性自身免疫性炎症'},
  {name: '颈椎病', department: '骨科', aliases: ['颈椎综合征'], description: '颈椎退行性病变致神经根或脊髓受压'},
  {name: '近视', department: '眼科', aliases: ['近视眼'], description: '屈光不正致远距离视物模糊'},
  {name: '白内障', department: '眼科', aliases: [], description: '晶状体混浊致视力下降'},
  {name: '干眼症', department: '眼科', aliases: ['干眼', '干燥性角膜结膜炎'], description: '泪液质或量异常致眼表损害'},
  {name: '急性结膜炎', department: '眼科', aliases: ['红眼病', '结膜炎'], description: '结膜急性炎症，常伴分泌物'},
  {name: '青光眼', department: '眼科', aliases: [], description: '眼压升高致视神经损害的不可逆眼病'},
  {name: '玻璃体混浊', department: '眼科', aliases: ['飞蚊症'], description: '玻璃体内出现混浊物致眼前黑影飘动'},
  {name: '小儿支气管炎', department: '儿科', aliases: ['儿童支气管炎'], description: '儿童支气管黏膜炎症，多继发于上感'},
  {name: '小儿腹泻病', department: '儿科', aliases: ['婴幼儿腹泻'], description: '多病原多因素致的大便次数增多'},
  {name: '手足口病', department: '儿科', aliases: ['HFMD'], description: '肠道病毒引起的儿童传染病'},
  {name: '幼儿急疹', department: '儿科', aliases: ['玫瑰疹'], description: '人类疱疹病毒 6 型致婴幼儿出疹性疾病'},
  {name: '营养不良', department: '儿科', aliases: ['营养缺乏'], description: '能量或营养素摄入不足致生长发育受限'},
  {name: '多囊卵巢综合征', department: '妇科', aliases: ['多囊', 'PCOS'], description: '内分泌代谢异常致月经异常及不孕'},
  {name: '子宫内膜异位症', department: '妇科', aliases: ['内异症'], description: '子宫内膜组织生长在子宫腔以外'},
  {name: '阴道炎', department: '妇科', aliases: ['阴道感染'], description: '阴道黏膜炎症，常伴白带异常'},
  {name: '子宫肌瘤', department: '妇科', aliases: ['肌瘤'], description: '子宫平滑肌组织良性肿瘤'},
  {name: '慢性盆腔炎', department: '妇科', aliases: ['盆腔炎'], description: '女性盆腔生殖器官及周围组织慢性炎症'}
] AS data
MERGE (disease:Disease {name: data.name})
SET disease.aliases = data.aliases,
    disease.department = data.department,
    disease.description = data.description,
    disease.node_id = 'disease:' + data.name;

// ========== 禁忌边：药品 ->CONTRAINDICATED_FOR-> 禁忌 ==========
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

// ========== 相互作用边：药品 ->INTERACTS_WITH-> 药品 ==========
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

// ========== 症状 ->INDICATES-> 疾病 ==========
// 沿边一跳扩展的核心路径：traverse_graph 从症状出发，检索关联疾病。
UNWIND [
  {symptom: '胸闷气短', disease: '心律失常'},
  {symptom: '胸闷气短', disease: '冠心病'},
  {symptom: '心悸心跳快', disease: '窦性心动过速'},
  {symptom: '心悸心跳快', disease: '甲状腺功能亢进'},
  {symptom: '血压偏高', disease: '原发性高血压'},
  {symptom: '胸痛伴冷汗', disease: '急性冠脉综合征'},
  {symptom: '下肢水肿', disease: '心功能不全'},
  {symptom: '咳嗽', disease: '急性支气管炎'},
  {symptom: '咳嗽', disease: '上呼吸道感染'},
  {symptom: '咳痰带血', disease: '肺结核'},
  {symptom: '咳痰带血', disease: '支气管扩张'},
  {symptom: '气喘呼吸困难', disease: '支气管哮喘'},
  {symptom: '气喘呼吸困难', disease: '慢阻肺'},
  {symptom: '长期咳痰', disease: '慢性支气管炎'},
  {symptom: '反复感冒', disease: '免疫力低下'},
  {symptom: '胃痛', disease: '消化性溃疡'},
  {symptom: '胃痛', disease: '慢性胃炎'},
  {symptom: '反酸烧心', disease: '胃食管反流病'},
  {symptom: '腹泻', disease: '急性肠炎'},
  {symptom: '便秘', disease: '功能性便秘'},
  {symptom: '腹胀', disease: '功能性消化不良'},
  {symptom: '头痛', disease: '紧张性头痛'},
  {symptom: '头痛', disease: '偏头痛'},
  {symptom: '头晕', disease: '良性阵发性位置性眩晕'},
  {symptom: '失眠', disease: '原发性失眠'},
  {symptom: '手麻', disease: '颈椎病'},
  {symptom: '手麻', disease: '腕管综合征'},
  {symptom: '面瘫', disease: '面神经炎'},
  {symptom: '口渴多饮', disease: '糖尿病'},
  {symptom: '多尿', disease: '糖尿病'},
  {symptom: '多尿', disease: '尿崩症'},
  {symptom: '体重下降', disease: '甲状腺功能亢进'},
  {symptom: '体重下降', disease: '糖尿病'},
  {symptom: '脖子增粗', disease: '甲状腺肿大'},
  {symptom: '怕热多汗', disease: '甲状腺功能亢进'},
  {symptom: '湿疹', disease: '湿疹'},
  {symptom: '荨麻疹', disease: '荨麻疹'},
  {symptom: '痤疮', disease: '痤疮'},
  {symptom: '皮肤瘙痒', disease: '皮肤瘙痒症'},
  {symptom: '皮肤起疹', disease: '接触性皮炎'},
  {symptom: '腰痛', disease: '腰椎间盘突出'},
  {symptom: '腰痛', disease: '腰肌劳损'},
  {symptom: '关节痛', disease: '骨关节炎'},
  {symptom: '颈肩痛', disease: '颈椎病'},
  {symptom: '扭伤', disease: '软组织损伤'},
  {symptom: '关节僵硬', disease: '骨关节炎'},
  {symptom: '关节僵硬', disease: '类风湿关节炎'},
  {symptom: '视力下降', disease: '近视'},
  {symptom: '视力下降', disease: '白内障'},
  {symptom: '眼睛干涩', disease: '干眼症'},
  {symptom: '眼红', disease: '急性结膜炎'},
  {symptom: '眼胀痛', disease: '青光眼'},
  {symptom: '眼前黑影', disease: '玻璃体混浊'},
  {symptom: '小儿发热', disease: '上呼吸道感染'},
  {symptom: '小儿咳嗽', disease: '小儿支气管炎'},
  {symptom: '小儿腹泻', disease: '小儿腹泻病'},
  {symptom: '小儿皮疹', disease: '手足口病'},
  {symptom: '小儿皮疹', disease: '幼儿急疹'},
  {symptom: '小儿厌食', disease: '营养不良'},
  {symptom: '月经不调', disease: '多囊卵巢综合征'},
  {symptom: '痛经', disease: '子宫内膜异位症'},
  {symptom: '白带异常', disease: '阴道炎'},
  {symptom: '经期延长', disease: '子宫肌瘤'},
  {symptom: '下腹隐痛', disease: '慢性盆腔炎'}
] AS data
MATCH (symptom:Symptom {name: data.symptom})
MATCH (disease:Disease {name: data.disease})
MERGE (symptom)-[:INDICATES]->(disease);

// ========== 疾病 ->TREATED_BY-> 科室 ==========
// 疾病归属科室，导诊推荐沿"症状->疾病->科室"链路定位就诊科室。
UNWIND [
  {disease: '心律失常', department: '心血管内科'},
  {disease: '冠心病', department: '心血管内科'},
  {disease: '窦性心动过速', department: '心血管内科'},
  {disease: '原发性高血压', department: '心血管内科'},
  {disease: '急性冠脉综合征', department: '心血管内科'},
  {disease: '心功能不全', department: '心血管内科'},
  {disease: '急性支气管炎', department: '呼吸内科'},
  {disease: '上呼吸道感染', department: '呼吸内科'},
  {disease: '肺结核', department: '呼吸内科'},
  {disease: '支气管扩张', department: '呼吸内科'},
  {disease: '支气管哮喘', department: '呼吸内科'},
  {disease: '慢阻肺', department: '呼吸内科'},
  {disease: '慢性支气管炎', department: '呼吸内科'},
  {disease: '免疫力低下', department: '呼吸内科'},
  {disease: '消化性溃疡', department: '消化内科'},
  {disease: '慢性胃炎', department: '消化内科'},
  {disease: '胃食管反流病', department: '消化内科'},
  {disease: '急性肠炎', department: '消化内科'},
  {disease: '功能性便秘', department: '消化内科'},
  {disease: '功能性消化不良', department: '消化内科'},
  {disease: '紧张性头痛', department: '神经内科'},
  {disease: '偏头痛', department: '神经内科'},
  {disease: '良性阵发性位置性眩晕', department: '神经内科'},
  {disease: '原发性失眠', department: '神经内科'},
  {disease: '腕管综合征', department: '神经内科'},
  {disease: '面神经炎', department: '神经内科'},
  {disease: '糖尿病', department: '内分泌科'},
  {disease: '尿崩症', department: '内分泌科'},
  {disease: '甲状腺肿大', department: '内分泌科'},
  {disease: '甲状腺功能亢进', department: '内分泌科'},
  {disease: '湿疹', department: '皮肤科'},
  {disease: '荨麻疹', department: '皮肤科'},
  {disease: '痤疮', department: '皮肤科'},
  {disease: '皮肤瘙痒症', department: '皮肤科'},
  {disease: '接触性皮炎', department: '皮肤科'},
  {disease: '腰椎间盘突出', department: '骨科'},
  {disease: '腰肌劳损', department: '骨科'},
  {disease: '骨关节炎', department: '骨科'},
  {disease: '软组织损伤', department: '骨科'},
  {disease: '类风湿关节炎', department: '骨科'},
  {disease: '颈椎病', department: '骨科'},
  {disease: '近视', department: '眼科'},
  {disease: '白内障', department: '眼科'},
  {disease: '干眼症', department: '眼科'},
  {disease: '急性结膜炎', department: '眼科'},
  {disease: '青光眼', department: '眼科'},
  {disease: '玻璃体混浊', department: '眼科'},
  {disease: '小儿支气管炎', department: '儿科'},
  {disease: '小儿腹泻病', department: '儿科'},
  {disease: '手足口病', department: '儿科'},
  {disease: '幼儿急疹', department: '儿科'},
  {disease: '营养不良', department: '儿科'},
  {disease: '多囊卵巢综合征', department: '妇科'},
  {disease: '子宫内膜异位症', department: '妇科'},
  {disease: '阴道炎', department: '妇科'},
  {disease: '子宫肌瘤', department: '妇科'},
  {disease: '慢性盆腔炎', department: '妇科'}
] AS data
MATCH (disease:Disease {name: data.disease})
MATCH (department:Department {name: data.department})
MERGE (disease)-[:TREATED_BY]->(department);

// ========== 症状 ->SUGGESTS_DEPARTMENT-> 科室 ==========
// 症状直接关联科室（跳过疾病），支撑快速导诊推荐。
UNWIND [
  {symptom: '胸闷气短', department: '心血管内科'},
  {symptom: '心悸心跳快', department: '心血管内科'},
  {symptom: '血压偏高', department: '心血管内科'},
  {symptom: '胸痛伴冷汗', department: '心血管内科'},
  {symptom: '下肢水肿', department: '心血管内科'},
  {symptom: '咳嗽', department: '呼吸内科'},
  {symptom: '咳痰带血', department: '呼吸内科'},
  {symptom: '气喘呼吸困难', department: '呼吸内科'},
  {symptom: '长期咳痰', department: '呼吸内科'},
  {symptom: '反复感冒', department: '呼吸内科'},
  {symptom: '胃痛', department: '消化内科'},
  {symptom: '反酸烧心', department: '消化内科'},
  {symptom: '腹泻', department: '消化内科'},
  {symptom: '便秘', department: '消化内科'},
  {symptom: '腹胀', department: '消化内科'},
  {symptom: '头痛', department: '神经内科'},
  {symptom: '头晕', department: '神经内科'},
  {symptom: '失眠', department: '神经内科'},
  {symptom: '手麻', department: '神经内科'},
  {symptom: '面瘫', department: '神经内科'},
  {symptom: '口渴多饮', department: '内分泌科'},
  {symptom: '多尿', department: '内分泌科'},
  {symptom: '体重下降', department: '内分泌科'},
  {symptom: '脖子增粗', department: '内分泌科'},
  {symptom: '怕热多汗', department: '内分泌科'},
  {symptom: '湿疹', department: '皮肤科'},
  {symptom: '荨麻疹', department: '皮肤科'},
  {symptom: '痤疮', department: '皮肤科'},
  {symptom: '皮肤瘙痒', department: '皮肤科'},
  {symptom: '皮肤起疹', department: '皮肤科'},
  {symptom: '腰痛', department: '骨科'},
  {symptom: '关节痛', department: '骨科'},
  {symptom: '颈肩痛', department: '骨科'},
  {symptom: '扭伤', department: '骨科'},
  {symptom: '关节僵硬', department: '骨科'},
  {symptom: '视力下降', department: '眼科'},
  {symptom: '眼睛干涩', department: '眼科'},
  {symptom: '眼红', department: '眼科'},
  {symptom: '眼胀痛', department: '眼科'},
  {symptom: '眼前黑影', department: '眼科'},
  {symptom: '小儿发热', department: '儿科'},
  {symptom: '小儿咳嗽', department: '儿科'},
  {symptom: '小儿腹泻', department: '儿科'},
  {symptom: '小儿皮疹', department: '儿科'},
  {symptom: '小儿厌食', department: '儿科'},
  {symptom: '月经不调', department: '妇科'},
  {symptom: '痛经', department: '妇科'},
  {symptom: '白带异常', department: '妇科'},
  {symptom: '经期延长', department: '妇科'},
  {symptom: '下腹隐痛', department: '妇科'}
] AS data
MATCH (symptom:Symptom {name: data.symptom})
MATCH (department:Department {name: data.department})
MERGE (symptom)-[:SUGGESTS_DEPARTMENT]->(department);

// ========== 药品 ->TREATS-> 疾病 ==========
// 药品治疗关系：药品节点 join 业务库 medication_id，治疗关系只存图谱（ADR-0006）。
UNWIND [
  {medication_id: 1, disease: '急性支气管炎'},
  {medication_id: 1, disease: '急性肠炎'},
  {medication_id: 2, disease: '紧张性头痛'},
  {medication_id: 2, disease: '偏头痛'},
  {medication_id: 3, disease: '荨麻疹'},
  {medication_id: 3, disease: '湿疹'},
  {medication_id: 5, disease: '上呼吸道感染'},
  {medication_id: 6, disease: '急性结膜炎'},
  {medication_id: 9, disease: '消化性溃疡'},
  {medication_id: 9, disease: '胃食管反流病'},
  {medication_id: 10, disease: '糖尿病'},
  {medication_id: 12, disease: '原发性高血压'},
  {medication_id: 13, disease: '原发性高血压'},
  {medication_id: 14, disease: '窦性心动过速'},
  {medication_id: 15, disease: '冠心病'},
  {medication_id: 17, disease: '冠心病'},
  {medication_id: 17, disease: '急性冠脉综合征'},
  {medication_id: 19, disease: '小儿腹泻病'},
  {medication_id: 20, disease: '功能性消化不良'},
  {medication_id: 22, disease: '支气管哮喘'},
  {medication_id: 23, disease: '支气管哮喘'},
  {medication_id: 26, disease: '甲状腺功能亢进'},
  {medication_id: 29, disease: '阴道炎'}
] AS data
MATCH (medication:Medication {medication_id: data.medication_id})
MATCH (disease:Disease {name: data.disease})
MERGE (medication)-[:TREATS]->(disease);

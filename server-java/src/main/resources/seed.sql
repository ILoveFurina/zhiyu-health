-- 幂等 seed：仅组织演示数据（虚构），ON CONFLICT DO NOTHING + 显式 id
-- 由 spring.sql.init.data-locations 在启动时执行

-- 票 49：3 家郑州虚构医院为 B 端正式业务数据（不建演示副本）；医院只存名称与等级。
INSERT INTO hospitals (id, name, level) VALUES
    (1, '郑州智愈综合医院', '三级甲等'),
    (2, '郑州智愈儿童医院', '三级甲等'),
    (3, '郑州智愈中心医院', '三级乙等')
ON CONFLICT (id) DO NOTHING;

-- 院区：服务城市由本表动态聚合（当前 seed 只覆盖郑州市 410100），
-- 地址/经纬度/就诊指引静态值均为虚构演示数据。B 端新增其他城市院区后自动形成新服务城市。
INSERT INTO hospital_campuses (id, hospital_id, name, city_code, city_name, address, longitude, latitude, floor, materials, precautions) VALUES
    (11, 1, '主院区', '410100', '郑州市', '郑州市金水区健康路 88 号', 113.6458, 34.7572,
     '门诊楼 1 层导诊台',
     E'身份证或医保卡\n既往病历与检查报告\n近期待用药品清单',
     E'建议提前 30 分钟到达并完成取号\n请携带既往病历便于医生参考\n就诊前避免进食油腻食物'),
    (12, 1, '郑东院区', '410100', '郑州市', '郑州市郑东新区龙湖中路 66 号', 113.7362, 34.7754,
     '门诊楼 1 层咨询台',
     E'身份证或医保卡\n既往病历与检查报告',
     E'建议提前 30 分钟到达并完成取号\n院区较大请预留步行时间'),
    (13, 1, '南院区', '410100', '郑州市', '郑州市二七区南屏路 21 号', 113.6521, 34.7012,
     '门诊楼 1 层导诊台',
     E'身份证或医保卡\n既往病历与检查报告',
     E'建议提前 30 分钟到达并完成取号'),
    (21, 2, '西院区', '410100', '郑州市', '郑州市中原区桐柏路 156 号', 113.5988, 34.7483,
     '门诊楼 1 层预检台',
     E'监护人身份证\n儿童医保卡\n预防接种证',
     E'建议提前 30 分钟到达\n发热患儿请先到预检台测温'),
    (22, 2, '东院区', '410100', '郑州市', '郑州市郑东新区心怡路 9 号', 113.7105, 34.7461,
     '门诊楼 1 层预检台',
     E'监护人身份证\n儿童医保卡\n既往检查报告',
     E'建议提前 30 分钟到达'),
    (31, 3, '主院区', '410100', '郑州市', '郑州市管城区城东路 45 号', 113.6822, 34.7335,
     '门诊楼 1 层咨询台',
     E'身份证或医保卡\n既往手术记录\n影像胶片或报告',
     E'建议提前 30 分钟到达并完成取号\n骨科患者请穿宽松衣物便于检查\n眼科患者请勿自行驾车前往')
ON CONFLICT (id) DO NOTHING;

-- 平台标准科室目录（科类 → 标准科室）：跨医院导航与号源匹配的唯一依据。
INSERT INTO standard_departments (id, category, name, sort_order) VALUES
    (1, '内科', '心血管内科', 1),
    (2, '内科', '呼吸内科', 2),
    (3, '内科', '消化内科', 3),
    (4, '内科', '神经内科', 4),
    (5, '内科', '内分泌科', 5),
    (6, '外科', '骨科', 1),
    (7, '皮肤科', '皮肤科', 1),
    (8, '五官科', '眼科', 1),
    (9, '儿科', '儿科', 1),
    (10, '妇产科', '妇科', 1)
ON CONFLICT (id) DO NOTHING;

-- 院区科室分类：每个院区独立维护一套分类体系。
INSERT INTO department_categories (id, campus_id, name, sort_order) VALUES
    (111, 11, '内科', 1),
    (112, 11, '外科', 2),
    (113, 11, '皮肤科', 3),
    (121, 12, '内科', 1),
    (122, 12, '外科', 2),
    (131, 13, '皮肤科', 1),
    (211, 21, '儿内科', 1),
    (212, 21, '儿外科', 2),
    (221, 22, '儿内科', 1),
    (222, 22, '儿外科', 2),
    (311, 31, '内科', 1),
    (312, 31, '外科', 2),
    (313, 31, '五官科', 3),
    (314, 31, '妇产科', 4)
ON CONFLICT (id) DO NOTHING;

-- 实际科室：归属院区 + 院区分类 + 非空标准科室映射（category 必须与 campus 同院区）。
INSERT INTO departments (id, campus_id, category_id, standard_department_id, name, floor, location) VALUES
    (1, 11, 111, 1, '心血管内科', '门诊楼 3 层', '东区 301 室'),
    (2, 11, 111, 2, '呼吸内科', '门诊楼 3 层', '东区 305 室'),
    (3, 11, 111, 3, '消化内科', '门诊楼 4 层', '东区 402 室'),
    (4, 12, 121, 4, '神经内科', '门诊楼 4 层', '东区 408 室'),
    (5, 12, 121, 5, '内分泌科', '门诊楼 5 层', '东区 503 室'),
    (6, 13, 131, 7, '皮肤科', '门诊楼 2 层', '西区 205 室'),
    (7, 21, 211, 9, '儿科', '门诊楼 3 层', '西区 302 室'),
    (8, 31, 312, 6, '骨科', '门诊楼 1 层', '西区 108 室'),
    (9, 31, 313, 8, '眼科', '门诊楼 2 层', '西区 210 室'),
    (10, 31, 314, 10, '妇科', '门诊楼 4 层', '西区 405 室')
ON CONFLICT (id) DO NOTHING;

INSERT INTO doctors (id, department_id, name, gender, birth_date, title, registration_fee, specialty, photo_url) VALUES
    (1, 1, '林知远', '男', '1975-03-15', '主任医师', 50.00, '高血压、冠心病、心律失常', 'photos/2026-08-07/lin-zhiyuan.jpg'),
    (2, 1, '周安宁', '女', '1982-07-22', '副主任医师', 30.00, '胸痛评估、心力衰竭', 'photos/2026-08-07/zhou-anning.jpg'),
    (3, 6, '陈清禾', '女', '1988-11-09', '主治医师', 20.00, '湿疹、荨麻疹、痤疮', 'photos/2026-08-07/chen-qinghe.jpg'),
    (4, 2, '苏明哲', '男', '1973-05-18', '主任医师', 50.00, '慢性咳嗽、哮喘、慢阻肺', 'photos/2026-08-07/su-mingzhe.jpg'),
    (5, 2, '李婉清', '女', '1985-09-30', '副主任医师', 30.00, '肺部感染、支气管扩张', 'photos/2026-08-07/li-wanqing.jpg'),
    (6, 3, '赵启明', '男', '1970-12-05', '主任医师', 50.00, '胃食管反流、消化性溃疡', 'photos/2026-08-07/zhao-qiming.jpg'),
    (7, 3, '吴佩珊', '女', '1990-04-14', '主治医师', 20.00, '慢性胃炎、功能性消化不良', 'photos/2026-08-07/wu-peishan.jpg'),
    (8, 4, '孙立航', '男', '1976-08-21', '主任医师', 50.00, '脑卒中、癫痫、头痛', 'photos/2026-08-07/sun-lihang.jpg'),
    (9, 4, '郑雅文', '女', '1983-02-27', '副主任医师', 30.00, '眩晕、面瘫、睡眠障碍', 'photos/2026-08-07/zheng-yawen.jpg'),
    (10, 5, '马俊杰', '男', '1978-06-11', '主任医师', 50.00, '糖尿病、甲状腺疾病', 'photos/2026-08-07/ma-junjie.jpg'),
    (11, 5, '何静怡', '女', '1986-10-03', '副主任医师', 30.00, '骨质疏松、肥胖代谢', 'photos/2026-08-07/he-jingyi.jpg'),
    (12, 8, '黄志远', '男', '1972-01-19', '主任医师', 50.00, '骨折、关节退变、颈肩腰腿痛', 'photos/2026-08-07/huang-zhiyuan.jpg'),
    (13, 8, '梁书瑶', '女', '1989-12-25', '主治医师', 20.00, '运动损伤、骨质疏松', 'photos/2026-08-07/liang-shuyao.jpg'),
    (14, 9, '冯雪松', '男', '1974-04-08', '主任医师', 50.00, '青光眼、白内障、眼底病', 'photos/2026-08-07/feng-xuesong.jpg'),
    (15, 7, '韩思敏', '女', '1987-08-16', '副主任医师', 30.00, '儿童呼吸道感染、过敏性疾病', 'photos/2026-08-07/han-simin.jpg')
ON CONFLICT (id) DO UPDATE SET
    department_id = EXCLUDED.department_id,
    name = EXCLUDED.name,
    gender = EXCLUDED.gender,
    birth_date = EXCLUDED.birth_date,
    title = EXCLUDED.title,
    registration_fee = EXCLUDED.registration_fee,
    specialty = EXCLUDED.specialty,
    photo_url = EXCLUDED.photo_url;

INSERT INTO medications (id, name, generic_name, specification, instructions, price, stock) VALUES
    (1, '阿莫西林胶囊', '阿莫西林', '0.25g*24粒', '口服；青霉素过敏者禁用', 18.50, 320),
    (2, '布洛芬缓释胶囊', '布洛芬', '0.3g*20粒', '口服；建议餐后服用', 22.00, 280),
    (3, '氯雷他定片', '氯雷他定', '10mg*12片', '口服；每日一次', 15.80, 410),
    (4, '华法林钠片', '华法林钠', '2.5mg*60片', '口服；须遵医嘱监测凝血指标', 68.00, 90),
    (5, '对乙酰氨基酚片', '对乙酰氨基酚', '0.5g*20片', '口服；按医嘱使用，不得超量', 9.50, 500),
    (6, '头孢克洛胶囊', '头孢克洛', '0.25g*12粒', '口服；头孢菌素过敏者禁用', 28.00, 200),
    (7, '阿奇霉素片', '阿奇霉素', '0.25g*6片', '口服；按医嘱完成疗程', 35.00, 150),
    (8, '左氧氟沙星片', '左氧氟沙星', '0.5g*5片', '口服；喹诺酮类过敏者禁用', 24.50, 180),
    (9, '奥美拉唑肠溶胶囊', '奥美拉唑', '20mg*14粒', '口服；通常餐前服用', 32.00, 260),
    (10, '二甲双胍片', '二甲双胍', '0.5g*20片', '口服；建议随餐服用', 12.00, 380),
    (11, '阿卡波糖片', '阿卡波糖', '50mg*30片', '口服；与第一口主食同服', 45.00, 120),
    (12, '氨氯地平片', '苯磺酸氨氯地平', '5mg*14片', '口服；按医嘱监测血压', 19.80, 300),
    (13, '厄贝沙坦片', '厄贝沙坦', '150mg*7片', '口服；按医嘱监测血压', 26.50, 240),
    (14, '美托洛尔缓释片', '琥珀酸美托洛尔', '47.5mg*7片', '口服；不可自行突然停药', 30.00, 170),
    (15, '阿托伐他汀钙片', '阿托伐他汀钙', '20mg*7片', '口服；按医嘱监测肝功能', 38.50, 160),
    (16, '瑞舒伐他汀钙片', '瑞舒伐他汀钙', '10mg*7片', '口服；按医嘱监测肝功能', 42.00, 140),
    (17, '阿司匹林肠溶片', '阿司匹林', '100mg*30片', '口服；有出血风险者须遵医嘱', 14.50, 360),
    (18, '氯吡格雷片', '硫酸氢氯吡格雷', '75mg*7片', '口服；有出血风险者须遵医嘱', 56.00, 110),
    (19, '蒙脱石散', '蒙脱石', '3g*10袋', '口服；与其他药物错开服用', 16.80, 290),
    (20, '多潘立酮片', '多潘立酮', '10mg*30片', '口服；通常餐前服用', 13.50, 270),
    (21, '西替利嗪片', '盐酸西替利嗪', '10mg*12片', '口服；每日一次', 17.00, 330),
    (22, '孟鲁司特钠片', '孟鲁司特钠', '10mg*5片', '口服；按医嘱使用', 39.00, 130),
    (23, '沙丁胺醇吸入气雾剂', '硫酸沙丁胺醇', '100μg*200揿', '吸入；按医嘱使用', 58.00, 85),
    (24, '布地奈德吸入粉雾剂', '布地奈德', '200μg*100吸', '吸入；使用后漱口', 72.00, 75),
    (25, '左甲状腺素钠片', '左甲状腺素钠', '50μg*100片', '口服；通常空腹服用', 48.00, 100),
    (26, '甲巯咪唑片', '甲巯咪唑', '5mg*100片', '口服；按医嘱监测血常规', 21.50, 190),
    (27, '碳酸钙D3片', '碳酸钙D3', '600mg*30片', '口服；与部分药物错开服用', 36.00, 230),
    (28, '维生素B2片', '核黄素', '5mg*100片', '口服；按医嘱使用', 8.00, 460),
    (29, '甲硝唑片', '甲硝唑', '0.2g*20片', '口服；用药期间及停药后避免饮酒', 11.50, 340),
    (30, '呋喃妥因肠溶片', '呋喃妥因', '50mg*100片', '口服；硝基呋喃类过敏者禁用', 27.50, 160)
ON CONFLICT (id) DO NOTHING;

-- 演示患者与健康档案（票 26）：显式 id + ON CONFLICT DO NOTHING 幂等。
-- patients/health_profiles/health_profile_allergies 均在 DemoResetService 清表清单内，
-- 重置 TRUNCATE 后随本段重灌。「林小满」带青霉素过敏（主线+禁忌拦截支线用），
-- 「周晓舟」无过敏（对照用）；mock 登录按 nickname 命中即复用，不新建（PatientService.mockLogin）。
INSERT INTO patients (id, nickname) VALUES
    (1, '林小满'),
    (2, '周晓舟')
ON CONFLICT (id) DO NOTHING;

INSERT INTO health_profiles (id, patient_id, display_name, gender, birth_date, relationship, active) VALUES
    (1, 1, '林小满', '女', '1992-05-12', '本人', TRUE),
    (2, 2, '周晓舟', '男', '1988-11-03', '本人', TRUE)
ON CONFLICT (id) DO NOTHING;

-- 林小满青霉素过敏：禁忌拦截支线对阿莫西林（medications.id=1，含青霉素类成分）的拦截依据。
INSERT INTO health_profile_allergies (health_profile_id, allergen) VALUES
    (1, '青霉素')
ON CONFLICT (health_profile_id, allergen) DO NOTHING;

-- 林小满历史体检报告解读与健康观测（票 61）：两条日期更早、内容完全虚构的 SUCCEEDED
-- 报告解读，与黄金样例报告（scripts/assets/report-samples/，2026-08-06）形成三点演示趋势。
-- 观测全部由报告确定性映射产生（source_type=REPORT_AI、UNVERIFIED、current=TRUE），
-- report_interpretation_id 非空，不用无来源观测偷造趋势；数值合理且不构成疾病诊断。
INSERT INTO report_interpretations (id, patient_id, health_profile_id, conversation_id, request_id,
    file_type, file_name, page_count, status, result_json, context_summary, error_code, disclaimer) VALUES
    (1, 1, 1, NULL, 'seed-report-lin-20260218', 'image', '演示体检报告-20260218.png', 1, 'SUCCEEDED',
     '{"summary":"本次体检各项受检指标均在参考范围内，血压、空腹血糖与总胆固醇未见明显异常。","sample_or_exam_date":"2026-02-18","report_date":"2026-02-20","items":[{"name":"身高","value":"165","unit":"cm","reference_range":"无","priority":"green","explanation":"身高为体格测量基础项。","action":"无需特殊处理。","page":1},{"name":"体重","value":"58.5","unit":"kg","reference_range":"无","priority":"green","explanation":"体重处于常见范围。","action":"保持均衡饮食。","page":1},{"name":"BMI","value":"21.5","unit":"kg/m2","reference_range":"18.5-24.0","priority":"green","explanation":"体质指数在参考范围内。","action":"保持现有生活方式。","page":1},{"name":"血压","value":"118/76","unit":"mmHg","reference_range":"90-140/60-90","priority":"green","explanation":"血压在参考范围内。","action":"定期监测即可。","page":1},{"name":"空腹血糖","value":"5.1","unit":"mmol/L","reference_range":"3.9-6.1","priority":"green","explanation":"空腹血糖在参考范围内。","action":"保持规律饮食。","page":1},{"name":"总胆固醇","value":"4.2","unit":"mmol/L","reference_range":"2.8-5.2","priority":"green","explanation":"总胆固醇在参考范围内。","action":"保持适量运动。","page":1},{"name":"丙氨酸氨基转移酶","value":"18","unit":"U/L","reference_range":"7-40","priority":"green","explanation":"肝功能指标在参考范围内。","action":"无需特殊处理。","page":1}],"actions":["保持规律作息与适量运动，建议每年复查一次。"],"unreadable":[]}',
     '2026-02-18 体检：身高 165cm、体重 58.5kg、血压 118/76mmHg、空腹血糖 5.1mmol/L、总胆固醇 4.2mmol/L，均在参考范围内。',
     NULL, '仅供参考，不替代医生诊断'),
    (2, 1, 1, NULL, 'seed-report-lin-20260520', 'image', '演示体检报告-20260520.png', 1, 'SUCCEEDED',
     '{"summary":"本次体检各项受检指标均在参考范围内，血压较上次略有波动但仍在正常范围，血型为 A 型 Rh 阳性。","sample_or_exam_date":"2026-05-20","report_date":"2026-05-22","items":[{"name":"身高","value":"165","unit":"cm","reference_range":"无","priority":"green","explanation":"身高为体格测量基础项。","action":"无需特殊处理。","page":1},{"name":"体重","value":"57.8","unit":"kg","reference_range":"无","priority":"green","explanation":"体重处于常见范围。","action":"保持均衡饮食。","page":1},{"name":"BMI","value":"21.2","unit":"kg/m2","reference_range":"18.5-24.0","priority":"green","explanation":"体质指数在参考范围内。","action":"保持现有生活方式。","page":1},{"name":"血压","value":"122/78","unit":"mmHg","reference_range":"90-140/60-90","priority":"green","explanation":"血压在参考范围内。","action":"定期监测即可。","page":1},{"name":"空腹血糖","value":"5.3","unit":"mmol/L","reference_range":"3.9-6.1","priority":"green","explanation":"空腹血糖在参考范围内。","action":"保持规律饮食。","page":1},{"name":"总胆固醇","value":"4.5","unit":"mmol/L","reference_range":"2.8-5.2","priority":"green","explanation":"总胆固醇在参考范围内。","action":"保持适量运动。","page":1},{"name":"ABO血型","value":"A","unit":"","reference_range":"无","priority":"green","explanation":"ABO 血型为 A 型。","action":"知晓即可。","page":1},{"name":"Rh血型","value":"阳性","unit":"","reference_range":"无","priority":"green","explanation":"Rh 血型为阳性。","action":"知晓即可。","page":1}],"actions":["保持规律作息与适量运动，建议每年复查一次。"],"unreadable":[]}',
     '2026-05-20 体检：体重 57.8kg、血压 122/78mmHg、空腹血糖 5.3mmol/L、总胆固醇 4.5mmol/L，均在参考范围内；血型 A 型 Rh 阳性。',
     NULL, '仅供参考，不替代医生诊断')
ON CONFLICT (id) DO NOTHING;

INSERT INTO health_observations (id, health_profile_id, report_interpretation_id, metric_code,
    value_numeric, value_category, unit, reference_range, observed_on,
    source_type, verification_status, current, supersedes_id) VALUES
    (1, 1, 1, 'HEIGHT', 165, NULL, 'cm', NULL, '2026-02-18', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (2, 1, 1, 'WEIGHT', 58.5, NULL, 'kg', NULL, '2026-02-18', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (3, 1, 1, 'BMI', 21.5, NULL, 'kg/m²', '18.5-24.0', '2026-02-18', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (4, 1, 1, 'SYSTOLIC_BP', 118, NULL, 'mmHg', '90-140', '2026-02-18', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (5, 1, 1, 'DIASTOLIC_BP', 76, NULL, 'mmHg', '60-90', '2026-02-18', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (6, 1, 1, 'FASTING_GLUCOSE', 5.1, NULL, 'mmol/L', '3.9-6.1', '2026-02-18', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (7, 1, 1, 'TOTAL_CHOLESTEROL', 4.2, NULL, 'mmol/L', '2.8-5.2', '2026-02-18', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (8, 1, 2, 'HEIGHT', 165, NULL, 'cm', NULL, '2026-05-20', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (9, 1, 2, 'WEIGHT', 57.8, NULL, 'kg', NULL, '2026-05-20', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (10, 1, 2, 'BMI', 21.2, NULL, 'kg/m²', '18.5-24.0', '2026-05-20', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (11, 1, 2, 'SYSTOLIC_BP', 122, NULL, 'mmHg', '90-140', '2026-05-20', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (12, 1, 2, 'DIASTOLIC_BP', 78, NULL, 'mmHg', '60-90', '2026-05-20', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (13, 1, 2, 'FASTING_GLUCOSE', 5.3, NULL, 'mmol/L', '3.9-6.1', '2026-05-20', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (14, 1, 2, 'TOTAL_CHOLESTEROL', 4.5, NULL, 'mmol/L', '2.8-5.2', '2026-05-20', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (15, 1, 2, 'ABO_BLOOD_TYPE', NULL, 'A', NULL, NULL, '2026-05-20', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL),
    (16, 1, 2, 'RH_D_BLOOD_TYPE', NULL, 'POSITIVE', NULL, NULL, '2026-05-20', 'REPORT_AI', 'UNVERIFIED', TRUE, NULL)
ON CONFLICT (id) DO NOTHING;

-- 排班（票 25/49）：用 CURRENT_DATE + interval 'N day' 动态生成今天起连续 14 天排班，
-- 保证任意演示日当天起仍有完整 14 天号源可挂。显式 id + ON CONFLICT DO NOTHING 保证幂等：
-- 重置 TRUNCATE schedules 后再执行本段可重新插入；已存在则跳过。
-- 覆盖全部 15 个医生、上午/下午两时段、每段 10 号，满足演示与并发抢号脚本需求。
-- 时段值与 TimeSlot 枚举字面量一致（schema 用枚举存中文）；中文读取靠 spring.sql.init.encoding=UTF-8。
INSERT INTO schedules (id, doctor_id, schedule_date, time_slot, total_slots, remaining_slots, is_active)
SELECT
    row_number() OVER (ORDER BY d.doctor_id, days.day, slots.slot) AS id,
    d.doctor_id,
    (CURRENT_DATE + (days.day || ' day')::interval)::date AS schedule_date,
    slots.slot AS time_slot,
    10 AS total_slots,
    10 AS remaining_slots,
    TRUE AS is_active
FROM (VALUES
    (1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15)
) AS d(doctor_id)
CROSS JOIN (VALUES
    (0),(1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13)
) AS days(day)
CROSS JOIN (VALUES
    ('上午'),('下午')
) AS slots(slot)
ON CONFLICT (id) DO NOTHING;

-- 知识库 50 场景（10 科室 × 5 症状，虚构非诊断性内容）。
-- 向量列由离线 embedding 工具产出的 seed-knowledge.sql 回填；此处只 seed 文本。
-- content 含症状+病因+建议科室+就医提示；红线场景（如胸痛伴冷汗）保留在库，
-- 红线规则在 server-java 先于一切执行，命中即中断不进检索，轻症仍可检索。
INSERT INTO knowledge_chunks (id, department, title, content) VALUES
    (1, '心血管内科', '胸闷气短',
     '常见于情绪紧张、劳累或轻度心律失常；若活动后加重、休息能缓解，多为功能性。建议记录发作频率与诱因，避免熬夜和过量咖啡因。若反复发作或伴有心悸，建议到心血管内科评估心电图。'),
    (2, '心血管内科', '心悸心跳快',
     '情绪激动、浓茶咖啡、睡眠不足常引起窦性心动过速；多为一过性。建议先观察是否与诱因相关，减少刺激因素。若心跳快持续不缓解或伴头晕黑蒙，建议到心血管内科做心电图或动态心电图。'),
    (3, '心血管内科', '血压偏高',
     '紧张、刚运动完或测量姿势不当可使血压一过性升高。建议安静休息 5 分钟后复测，并固定时间多次测量记录。若非同日三次测量均偏高，建议到心血管内科进一步评估是否需干预。'),
    (4, '心血管内科', '胸痛伴冷汗',
     '胸痛伴冷汗、放射至左肩或下颌、持续不缓解，可能是急性冠脉综合征等危重情况，须立即拨打急救电话就医，不要自行前往。本条仅作知识提醒，任何剧烈胸痛均属红线，系统会优先拦截并引导紧急就医。'),
    (5, '心血管内科', '下肢水肿',
     '久站久坐引起的轻度水肿多为静脉回流不畅；若双侧对称、晨轻暮重，需排查心功能问题。建议减少长时间站立，休息时抬高下肢。若水肿持续加重或伴气短，建议到心血管内科评估。'),
    (6, '呼吸内科', '咳嗽',
     '急性咳嗽多由上呼吸道感染引起，通常 1-2 周自愈。建议多饮温水、避免冷空气刺激，观察痰量与颜色。若咳嗽超过两周、伴黄脓痰或发热，建议到呼吸内科排查支气管炎或肺炎。'),
    (7, '呼吸内科', '咳痰带血',
     '痰中带血可能源于剧烈咳嗽损伤黏膜，也可能提示肺部疾病。建议记录血量与颜色，避免剧烈呛咳。若反复出现或血量较多，建议尽快到呼吸内科做胸部影像学检查。'),
    (8, '呼吸内科', '气喘呼吸困难',
     '活动后轻度气喘可见于体能下降或哮喘；若静息时也喘、夜间憋醒，需警惕。建议避免已知过敏原和冷空气，记录发作时间。若呼吸困难进行性加重或口唇发紫，建议立即到呼吸内科或急诊。'),
    (9, '呼吸内科', '长期咳痰',
     '每日咳痰持续三个月以上可能为慢性支气管炎等慢阻肺表现，常见于长期吸烟者。建议戒烟、避免烟雾刺激，注意保暖。建议到呼吸内科做肺功能检查评估气流受限情况。'),
    (10, '呼吸内科', '反复感冒',
     '免疫力波动、温差变化可使感冒频繁。建议规律作息、适度锻炼、勤洗手，注意增减衣物。若每次感冒都迁延或并发下呼吸道感染，建议到呼吸内科评估是否存在气道高反应。'),
    (11, '消化内科', '胃痛',
     '饮食不规律、辛辣刺激或精神紧张常引起功能性胃痛。建议规律进餐、少食多餐，避免生冷和刺激性食物。若胃痛反复发作、夜间痛醒或伴黑便，建议到消化内科评估是否有溃疡。'),
    (12, '消化内科', '反酸烧心',
     '胃食管反流常表现为胸骨后烧灼感，饱餐后或平卧时加重。建议餐后勿立即躺下、睡前 2 小时不再进食、抬高床头。若症状频繁影响生活，建议到消化内科评估是否需抑酸治疗。'),
    (13, '消化内科', '腹泻',
     '急性腹泻多由饮食不洁或病毒感染引起，以补液和清淡饮食为主。建议少量多次饮用温水或口服补液盐，避免乳制品和高脂食物。若腹泻伴高热、脓血便或持续超过三天，建议到消化内科就诊。'),
    (14, '消化内科', '便秘',
     '膳食纤维不足、饮水少、久坐是常见原因。建议增加蔬果和全谷物摄入、每日饮水 1500ml 以上、养成定时排便习惯。若便秘长期不缓解或伴排便习惯改变，建议到消化内科排查。'),
    (15, '消化内科', '腹胀',
     '进食过快、产气食物或消化不良可致腹胀。建议细嚼慢咽、减少豆类和碳酸饮料、餐后适当活动。若腹胀持续加重或伴腹痛、消瘦，建议到消化内科进一步检查。'),
    (16, '神经内科', '头痛',
     '紧张性头痛和偏头痛最常见，多与睡眠不足、压力、强光有关。建议规律作息、记录头痛诱因与部位，必要时在安静环境休息。若头痛突然剧烈、伴发热或视物模糊，建议到神经内科排查。'),
    (17, '神经内科', '头晕',
     '低血糖、脱水、久蹲起立或前庭问题均可头晕。建议缓慢改变体位、保证饮水和进食。若头晕反复发作、伴耳鸣或平衡障碍，建议到神经内科或耳鼻喉评估前庭功能。'),
    (18, '神经内科', '失眠',
     '焦虑、作息紊乱、睡前使用电子设备是常见诱因。建议固定就寝时间、睡前避免咖啡因和强光、营造安静环境。若失眠持续影响白天功能，建议到神经内科或睡眠门诊评估。'),
    (19, '神经内科', '手麻',
     '长时间压迫或颈椎问题可引起手麻，多为短暂性。建议调整姿势、避免长时间低头，活动颈肩。若手麻反复或持续、呈手套样分布，建议到神经内科排查神经病变。'),
    (20, '神经内科', '面瘫',
     '面部一侧无力、口角歪斜可能是面神经炎，常与受凉或病毒感染相关。建议尽早到神经内科就诊，早期治疗预后更好；注意保护患侧眼睛避免角膜损伤。'),
    (21, '内分泌科', '口渴多饮',
     '饮水不足或高盐饮食后口渴属正常；若持续口渴伴多尿、体重下降，需警惕糖尿病。建议记录饮水量与尿量，避免含糖饮料。建议到内分泌科查空腹血糖和糖化血红蛋白。'),
    (22, '内分泌科', '多尿',
     '饮水多、利尿食物或药物可致多尿；若伴口渴多饮和体重下降，需排查糖尿病。建议记录尿量与夜尿次数。建议到内分泌科做血糖和肾功能相关检查。'),
    (23, '内分泌科', '体重下降',
     '刻意节食或压力可致体重下降；若短期内无明显原因消瘦，需排查内分泌或代谢疾病。建议记录体重变化趋势和饮食情况。建议到内分泌科评估甲状腺功能和血糖。'),
    (24, '内分泌科', '脖子增粗',
     '甲状腺肿大可表现为颈部增粗，可能伴功能异常。建议观察是否有吞咽不适或心慌手抖。建议到内分泌科做甲状腺超声和功能检查明确性质。'),
    (25, '内分泌科', '怕热多汗',
     '甲亢常表现为怕热、多汗、心慌和易激动。建议记录症状持续时间，避免过度劳累和咖啡因。建议到内分泌科查甲状腺功能以明确是否需治疗。'),
    (26, '皮肤科', '湿疹',
     '湿疹表现为红斑、丘疹伴瘙痒，常与过敏和皮肤屏障受损有关。建议避免过热水洗烫、勤用保湿霜、记录可疑过敏原。若反复发作或面积扩大，建议到皮肤科评估规范用药。'),
    (27, '皮肤科', '荨麻疹',
     '荨麻疹为风团伴瘙痒，常因食物、药物或感染诱发，多为自限性。建议记录发作前接触史、避免搔抓。若反复发作或伴胸闷，建议到皮肤科排查诱因并用药。'),
    (28, '皮肤科', '痤疮',
     '青春期激素波动、皮脂分泌旺盛和毛孔堵塞是主因。建议温和清洁、避免挤压、规律作息少糖少油。若炎症明显或留疤，建议到皮肤科进行规范治疗。'),
    (29, '皮肤科', '皮肤瘙痒',
     '干燥、过敏或内科疾病均可致瘙痒。建议避免过热洗澡、加强保湿、穿着宽松棉质衣物。若瘙痒广泛持续或伴黄疸，建议到皮肤科排查系统性疾病。'),
    (30, '皮肤科', '皮肤起疹',
     '皮疹形态多样，可能与感染、过敏或免疫相关。建议记录起疹时间、形态和进展，避免自行涂药掩盖。若伴发热或快速扩散，建议到皮肤科面诊明确诊断。'),
    (31, '骨科', '腰痛',
     '久坐、姿势不良或腰肌劳损是常见原因，多为酸胀痛。建议保持正确坐姿、加强腰背肌锻炼、避免久坐久站。若腰痛伴下肢放射痛或麻木，建议到骨科排查椎间盘问题。'),
    (32, '骨科', '关节痛',
     '运动过量、退变或受凉可致关节疼痛。建议急性期休息、避免过度负重、注意保暖。若关节红肿热痛或活动受限持续，建议到骨科评估是否有炎症或损伤。'),
    (33, '骨科', '颈肩痛',
     '长期低头、伏案工作常引起颈肩肌筋膜炎。建议调整屏幕高度、定时活动颈肩、避免受凉。若伴上肢麻木或头晕，建议到骨科排查颈椎病。'),
    (34, '骨科', '扭伤',
     '踝膝关节扭伤后应立即停止活动、冰敷并抬高患肢，避免揉搓和热敷。建议记录肿胀和活动受限情况。若剧痛、无法负重或明显畸形，建议到骨科排除骨折。'),
    (35, '骨科', '关节僵硬',
     '晨起关节僵硬常见于退变或炎症性疾病，活动后缓解多为骨关节炎。建议适度活动、避免长时间固定姿势。若僵硬超过半小时或对称多关节，建议到骨科或风湿评估。'),
    (36, '眼科', '视力下降',
     '用眼过度、近视进展或眼底病变均可致视力下降。建议控制用眼时间、注意读写距离、定期休息远眺。若视力短期内明显下降，建议到眼科做视力与眼底检查。'),
    (37, '眼科', '眼睛干涩',
     '长时间看屏幕、空调环境和泪液分泌减少可致干眼。建议增加眨眼频率、使用人工泪液、调整屏幕位置。若干涩伴畏光或异物感持续，建议到眼科评估干眼程度。'),
    (38, '眼科', '眼红',
     '结膜炎、用眼疲劳或异物刺激可使眼白发红。建议勿揉眼、注意手卫生、避免共用毛巾。若眼红伴分泌物增多或视力受影响，建议到眼科明确是否需抗感染治疗。'),
    (39, '眼科', '眼胀痛',
     '视疲劳或青光眼等可致眼胀痛，伴头痛恶心时需警惕。建议避免暗处长时间用眼、定时休息。若眼胀痛伴视力模糊或虹视，建议尽快到眼科测眼压排查青光眼。'),
    (40, '眼科', '眼前黑影',
     '玻璃体混浊引起的飞蚊症多为良性，尤其近视者常见。建议观察黑影是否突然增多或伴闪光感。若黑影骤增或视野缺损，建议尽快到眼科排查视网膜病变。'),
    (41, '儿科', '小儿发热',
     '感染是儿童发热最常见原因，低中度发热可观察精神状态、多饮温水、物理降温。建议记录体温曲线。若 3 个月以下婴儿发热、高热不退或精神萎靡，建议到儿科就诊。'),
    (42, '儿科', '小儿咳嗽',
     '儿童咳嗽多由上呼吸道感染引起，注意保持空气湿润、多饮温水。建议观察咳嗽性质和痰量。若咳嗽伴喘息、呼吸困难或持续超过一周，建议到儿科评估。'),
    (43, '儿科', '小儿腹泻',
     '儿童腹泻易致脱水，重点是补液和饮食调整，继续喂养、口服补液盐。建议观察大便性状和尿量。若出现脱水征、血便或高热，建议到儿科及时就诊。'),
    (44, '儿科', '小儿皮疹',
     '儿童皮疹可见于病毒感染、过敏等，形态多样。建议记录出疹顺序和伴随症状，避免搔抓。若伴高热不退、精神差或皮疹快速蔓延，建议到儿科面诊。'),
    (45, '儿科', '小儿厌食',
     '饮食习惯不良、零食过多或疾病均可致食欲下降。建议规律进餐、减少零食、营造良好就餐氛围。若长期厌食伴体重增长缓慢，建议到儿科评估营养状况。'),
    (46, '妇科', '月经不调',
     '压力、体重骤变或内分泌波动可使周期紊乱。建议记录周期天数和经量，保持规律作息。若月经频繁推迟、经量异常或伴其他不适，建议到妇科评估激素水平。'),
    (47, '妇科', '痛经',
     '原发性痛经常见于年轻女性，热敷和休息可缓解。建议经期避免生冷和剧烈运动、注意保暖。若痛经进行性加重或影响日常，建议到妇科排查子宫内膜异位症等。'),
    (48, '妇科', '白带异常',
     '白带量、色、味的改变可能提示感染。建议穿棉质内裤、注意清洁、避免过度冲洗。若白带伴异味、瘙痒或颜色异常，建议到妇科做分泌物检查。'),
    (49, '妇科', '经期延长',
     '内分泌失调、子宫病变等可使经期延长。建议记录经期天数和经量变化。若连续数月经期超过 7 天或经量明显增多，建议到妇科排查原因。'),
    (50, '妇科', '下腹隐痛',
     '慢性盆腔不适、排卵痛或肠道问题均可致下腹隐痛。建议记录疼痛与月经周期关系。若隐痛持续或加重、伴发热或异常出血，建议到妇科进一步检查。')
ON CONFLICT (id) DO NOTHING;

-- 处方模板（票 47）：doctor.lin（doctors.id=1，心内科）与 doctor.zhou（doctors.id=2）各备虚构常用药组合，
-- 药品 id 均引用上方 medications 既有条目；显式 id + ON CONFLICT DO NOTHING 幂等。
INSERT INTO prescription_templates (id, name, doctor_id) VALUES
    (1, '高血压基础用药', 1),
    (2, '冠心病二级预防', 1),
    (3, '胸痛随访用药', 2)
ON CONFLICT (id) DO NOTHING;

INSERT INTO prescription_template_items (id, template_id, medication_id, dosage, frequency, duration, notes) VALUES
    (1, 1, 12, '5mg', '每日1次', '30天', '晨起服用，按医嘱监测血压'),
    (2, 1, 13, '150mg', '每日1次', '30天', '按医嘱监测血压'),
    (3, 2, 17, '100mg', '每日1次', '30天', '餐后服用，有出血风险者须遵医嘱'),
    (4, 2, 15, '20mg', '每晚1次', '30天', '按医嘱监测肝功能'),
    (5, 3, 17, '100mg', '每日1次', '14天', '有出血风险者须遵医嘱'),
    (6, 3, 16, '10mg', '每晚1次', '14天', '按医嘱监测肝功能')
ON CONFLICT (id) DO NOTHING;

-- 显式 id 不推进 identity 序列：对齐到当前 MAX(id)，避免后续业务写入撞主键
SELECT setval('hospitals_id_seq', (SELECT MAX(id) FROM hospitals));
SELECT setval('hospital_campuses_id_seq', (SELECT MAX(id) FROM hospital_campuses));
SELECT setval('standard_departments_id_seq', (SELECT MAX(id) FROM standard_departments));
SELECT setval('department_categories_id_seq', (SELECT MAX(id) FROM department_categories));
SELECT setval('departments_id_seq', (SELECT MAX(id) FROM departments));
SELECT setval('doctors_id_seq', (SELECT MAX(id) FROM doctors));
SELECT setval('medications_id_seq', (SELECT MAX(id) FROM medications));
SELECT setval('patients_id_seq', (SELECT COALESCE(MAX(id), 1) FROM patients));
SELECT setval('health_profiles_id_seq', (SELECT COALESCE(MAX(id), 1) FROM health_profiles));
SELECT setval('health_profile_allergies_id_seq', (SELECT COALESCE(MAX(id), 1) FROM health_profile_allergies));
SELECT setval('report_interpretations_id_seq', (SELECT COALESCE(MAX(id), 1) FROM report_interpretations));
SELECT setval('health_observations_id_seq', (SELECT COALESCE(MAX(id), 1) FROM health_observations));
SELECT setval('schedules_id_seq', (SELECT COALESCE(MAX(id), 1) FROM schedules));
SELECT setval('knowledge_chunks_id_seq', (SELECT MAX(id) FROM knowledge_chunks));
SELECT setval('prescription_templates_id_seq', (SELECT COALESCE(MAX(id), 1) FROM prescription_templates));
SELECT setval('prescription_template_items_id_seq', (SELECT COALESCE(MAX(id), 1) FROM prescription_template_items));

-- 补回 doctors.department_id→departments(id) FK 约束。
-- 必须在 departments/doctors 数据重灌之后执行：schema.sql 里 DROP departments CASCADE
-- 会移除 doctors 上的 FK，但 doctors 旧行驻留为孤儿行；此处两侧数据都已就位，
-- 再补 FK 才能通过校验（提前补会在 schema 阶段报 fk_doctors_department 违规，阻断启动）。
ALTER TABLE doctors ADD CONSTRAINT fk_doctors_department
    FOREIGN KEY (department_id) REFERENCES departments(id);

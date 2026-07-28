-- 幂等 seed：仅组织演示数据（虚构），ON CONFLICT DO NOTHING + 显式 id
-- 由 spring.sql.init.data-locations 在启动时执行

INSERT INTO hospitals (id, name, level, address, longitude, latitude) VALUES
    (1, '智愈市人民医院', '三级甲等', '智愈市安康路 88 号', 121.4737, 31.2304)
ON CONFLICT (id) DO NOTHING;

INSERT INTO departments (id, hospital_id, name, floor, location) VALUES
    (1, 1, '心血管内科', '门诊楼 3 层', '东区 301 室'),
    (2, 1, '皮肤科', '门诊楼 2 层', '西区 205 室')
ON CONFLICT (id) DO NOTHING;

INSERT INTO doctors (id, department_id, name, title, specialty, photo_url) VALUES
    (1, 1, '林知远', '主任医师', '高血压、冠心病、心律失常', 'https://example.com/demo/lin-zhiyuan.jpg'),
    (2, 1, '周安宁', '副主任医师', '胸痛评估、心力衰竭', 'https://example.com/demo/zhou-anning.jpg'),
    (3, 2, '陈清禾', '主治医师', '湿疹、荨麻疹、痤疮', 'https://example.com/demo/chen-qinghe.jpg')
ON CONFLICT (id) DO NOTHING;

-- 显式 id 不推进 identity 序列：对齐到当前 MAX(id)，避免后续业务写入撞主键
SELECT setval('hospitals_id_seq', (SELECT MAX(id) FROM hospitals));
SELECT setval('departments_id_seq', (SELECT MAX(id) FROM departments));
SELECT setval('doctors_id_seq', (SELECT MAX(id) FROM doctors));

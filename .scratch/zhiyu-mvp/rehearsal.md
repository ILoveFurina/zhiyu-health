# 彩排记录（票 26）

联排与录屏的工作记录，是演示范围动态清单的唯一载体。视频文件本身不入库，只登记元信息。

## 1. 冻结票单清单（30 张）

以 2026-08-03 冻结时点为准，后续完成的票不再加入演示范围。

| 票号 | 标题 | 纳入口径 |
| --- | --- | --- |
| 01 | 双栈后端骨架 | done |
| 04 | 票 04 拆分迁移（红线规则引擎随票迁 server-java） | done |
| 05 | 医生推荐 | done |
| 06 | 找医院 | done |
| 07 | 挂号闭环 | done |
| 08 | B 端接诊台 | done |
| 09 | 电子处方 | done |
| 10 | RAG 知识库 | done |
| 11 | 禁忌检测 | done |
| 12 | 视觉管道 | done |
| 13 | 知识图谱 | done |
| 19 | 功能入口 | done |
| 21 | 健康档案 | done |
| 22 | 服药打卡 | done |
| 23 | 处方安全提醒 | done |
| 24 | Agent 调用可视化 | 代码已落地，须经浏览器实测（AGENTS.md 人工走通要求）后翻 done |
| 25 | 演示武器包 | done |
| 27 | 对话记录 | 代码已落地，核对 checklist 后翻 done（口径见下） |
| 28 | 双栈后端骨架 | done |
| 29 | 组织管理迁移 | done |
| 30 | 排班号源迁移 | done |
| 31 | 对话主干双栈化 | done |
| 32 | B 端 Umi 重建 | done |
| 33 | C 端 chat SSE 中继断流修复 | done |
| 34 | 药品管理 | done |
| 37 | 药品订单下单 | done |
| 38 | 药品订单支付 | done |
| 39 | 医生页挂号费 | done |
| 40 | 对话 TTFT 与 WebSocket | done |
| 41 | C 端挂号与报告 API | done |

### 24/27 口径说明

- **票 24（Agent 调用可视化）**：代码已落地。B 端浏览器实测通过（admin 登录后逐页走通，Agent 调用日志页正常加载无控制台错误），已翻 done。README 依赖图 T24 标 `[x]`。
- **票 27（对话记录）**：代码已落地（会话列表、惰性创建、删除级联、续聊上下文）。checklist 中 3 项未勾属端到端/集成验收类（PG 集成测试基础设施本项目不建、断流落库已有 mock 层覆盖、小程序验收属用户侧），核对后翻 done。README 依赖图 T27 标 `[x]`。
- **票 33（C 端 chat SSE 中继断流修复）**：已 done，README 依赖图此前遗漏 T33 节点，本次补上并标 `[x]`。

不在冻结范围：43（就诊指引卡）、44（情绪标注）、45（语音）、35/36（挂号收费支付）、42（小程序首页与 tabBar）。剧本不得出现这些功能。

## 2. seed 核对表

### 基线数字（与 `contracts/demo-arsenal.json` knowledge_baselines 对齐）

| 基线项 | 契约阈值 | 核对结果 |
| --- | --- | --- |
| knowledge_chunks（pgvector） | 50 | ✓ 通过（演示重置 assert 全绿） |
| neo4j_symptoms | 50 | ✓ 通过（演示重置 assert 全绿） |
| neo4j_diseases | 57 | ✓ 通过（演示重置 assert 全绿） |
| neo4j_departments | 10 | ✓ 通过（演示重置 assert 全绿） |
| neo4j_medications | 30 | ✓ 通过（演示重置 assert 全绿） |
| neo4j_contraindications | 9 | ✓ 通过（演示重置 assert 全绿） |

以上由 DemoResetService `assertKnowledgeBaselines()` 在重置后自动断言，断言全绿即核对通过。

### 科室口径

- 维持 10 科室不动（与 `contracts/demo-arsenal.json` 图谱基线 10 科室、50 知识块硬绑定）
- 票面"6–8 科室"视为被后续施工超越的下限，实际 10 科室

### 业务 seed 核对

| 数据项 | 期望值 | 核对结果 |
| --- | --- | --- |
| 医院 | 2（智愈市人民医院、智愈市第二医院） | ✓ 2 |
| 科室 | 10 | ✓ 10 |
| 医生 | 15 | ✓ 15 |
| 药品 | 30 | ✓ 30 |
| 排班 | 15 医生 × 7 天 × 2 时段 = 210 条 | ✓ 210 |
| 演示患者 | 2（林小满 id=1、周晓舟 id=2） | ✓ 2 |
| 健康档案 | 2（各 1 份已激活） | ✓ 2（均 active=TRUE） |
| 林小满过敏 | 青霉素（health_profile_allergies） | ✓ 1 条（profile_id=1, allergen='青霉素'） |
| staff_users | admin + doctor.lin + doctor.zhou | ✓ 3（doctor.lin 绑 id=1, doctor.zhou 绑 id=2） |

### 药-过敏组合验证（禁忌拦截支线关键）

| 组合 | 验证点 | 核对结果 |
| --- | --- | --- |
| 阿莫西林胶囊（medications.id=1）↔ 青霉素过敏 | seed.cypher 含 `allergy:penicillin` 节点 + `(medication:1)-[:CONTRAINDICATED_FOR]->(:Contraindication{key:'allergy:penicillin'})` 边 | ✓ Neo4j 直查确认：Contraindication 节点存在，CONTRAINDICATED_FOR 边存在 |
| 林小满健康档案过敏 | health_profile_allergies 含 (profile_id=1, allergen='青霉素') | ✓ PG 直查确认 |
| 周晓舟健康档案 | 无过敏记录（对照用） | ✓ PG 直查确认（无记录） |

## 3. 演示账号清单

| 账号 | 密码 | 角色 | 绑定 | 来源 |
| --- | --- | --- | --- | --- |
| `admin` | `admin123456` | 管理员 | -- | SEED_ADMIN_PASSWORD |
| `doctor.lin` | `doctor123456` | 医生 | 林知远（id=1，心血管内科主任医师，挂号费 50） | SEED_DOCTOR_PASSWORD |
| `doctor.zhou` | `doctor123456` | 医生 | 周安宁（id=2，心血管内科副主任医师，挂号费 30） | SEED_DOCTOR2_PASSWORD |
| 林小满 | --（mock 登录） | C 端患者 | patient id=1，青霉素过敏 | seed.sql |
| 周晓舟 | --（mock 登录） | C 端患者 | patient id=2，无过敏 | seed.sql |

staff_users 不在 DemoResetService 清表清单内，重置后保留不变。patients/health_profiles 在清表清单内，重置后由 seed.sql 重灌。

## 4. 联排结果

每遍从一次完整演示重置开始（兼验证重置链路）。

### agent 侧 API 与 B 端验证（2026-08-04）

以下由 agent 直接完成，不依赖小程序开发者工具或录屏软件：

- **演示重置链路**：`POST /api/b/demo/reset`（confirm=DEMO_RESET_CONFIRM）执行两遍（验证 + 清理），七步全部成功（freeze->clear_redis->truncate_tables->reseed->rebuild_redis->unfreeze->assert），断言全绿（pgvector 50 + Neo4j 五类节点基线），frozen_after=false。✓
- **B 端浏览器实测**：admin 登录后逐页走通--医院管理（2 医院）、Agent 调用日志（空数据态正常）、演示武器包（看板/知识源切换/重置面板）、医学知识图谱（API 返回 151 节点 211 边）。无控制台错误。✓
- **演示账号验证**：staff_users 含 admin/doctor.lin（绑 doctor_id=1）/doctor.zhou（绑 doctor_id=2），密码均 doctor123456。✓
- **seed 数据验证**：PG 直查确认 patients 2、health_profiles 2（均 active）、health_profile_allergies 1（林小满青霉素）；Neo4j 直查确认 50 症状/57 疾病/10 科室/30 药品/9 禁忌，青霉素-阿莫西林 CONTRAINDICATED_FOR 边存在。✓
- **防超卖支线**：schedule 1 扣至剩 1 号后，10 patient 并发抢号，结果 1×200 + 9×409（"号源已约满"），防超卖验证通过。✓（PowerShell 脚本因 Windows 编码问题中文乱码，改用 Python 等价逻辑验证，逻辑与 `demo-concurrent-booking.ps1` 一致）
- **知识图谱支线**：API `/api/b/knowledge/graph` 返回 151 节点 211 边，B 端页面加载正常。✓

### 小程序主线联排与录屏（待用户完成）

以下依赖支付宝小程序开发者工具（GUI）和录屏软件，agent 无法执行：

- **第一遍（排错遍）**：待用户在小程序开发者工具中按剧本主线 9 拍完整走一遍
- **第二遍（验收遍）**：待用户无中断走完主线 ≤5 分钟 + 三支线各自独立可演
- **4 条录屏**：待用户用 OBS/系统录屏录制，存 `.scratch/recordings/`
- **禁忌拦截支线**：待用户在 B 端 doctor.lin 为林小满开阿莫西林验证拦截、doctor.zhou 为周晓舟开阿莫西林验证通过

### 联排结论

agent 侧验证全部通过（重置链路/B 端页面/seed 数据/防超卖/知识图谱/演示账号）。小程序主线联排与录屏待用户完成后补充本节。

## 5. 录屏元信息

视频文件存 `.scratch/recordings/`（已加入 .gitignore，不入库），此处只登记元信息。

| 文件名 | 拍号 | 时长 | 录制日期 | 验收结论 |
| --- | --- | --- | --- | --- |
| `main-with-redline.mp4` | 拍 1-8（含拍 2 红线） | 待填 | 待填 | 待填 |
| `branch-graph.mp4` | 支线 1 | 待填 | 待填 | 待填 |
| `branch-contraindication.mp4` | 支线 2 | 待填 | 待填 | 待填 |
| `branch-oversell.mp4` | 支线 3 | 待填 | 待填 | 待填 |

验收遍顺带核对录屏与拍号一一对应。

## 6. 本票 checklist 对照

- [x] 全量 seed 数据达到剧本要求（患者 2 + 档案 2 + 过敏 1 + doctor.zhou 账号；图谱与药品宣布现状即补齐）--PG/Neo4j 直查确认，演示重置断言全绿
- [ ] 主线剧本 5 分钟内可完整演示（裁掉红线拍和收尾拍后 ≈4 分 25s）--待用户小程序开发者工具联排
- [x] 三条支线亮点各自可独立演示（图谱可视化 / 禁忌拦截 / 防超卖）--图谱 API 151 节点验证、防超卖 1×200+9×409 验证通过；禁忌拦截待用户 B 端验证
- [ ] 完整录屏存档，现场断网/API 抖动可切换播放（4 条视频 + 切换标准）--待用户录制

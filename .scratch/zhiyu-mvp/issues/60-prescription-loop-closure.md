# 60 - 处方闭环串联与创新点补齐

**What to build:** 三块工作同一分支施工（grilling 共识 + 题目创新点审视；驳回语义见 ADR-0030）。

**A. 处方闭环串联与患者感知**：让 C 端闭环从"问诊已完成"一路走到药品订单 DONE——

1. C 端处方全程可见：`GET /api/c/prescriptions` 返回全部状态（PENDING「审核中」/ APPROVED 可下单 / REJECTED「未通过」），不再只回 APPROVED；用药解读仍只随 APPROVED 出现（`ck_prescriptions_patient_visibility` 不动）。
2. 问诊完成页加"查看电子处方"出口，按处方状态分流文案（审核中 / 去购药 / 未通过引导）。
3. 审核结果站内消息：APPROVED / REJECTED 各一条；`in_app_messages` 加 `related_prescription_id` 外键列与 `UNIQUE(related_prescription_id, type)` 幂等（对齐就诊指引卡先例）；消息类型与文案入 `contracts/prescription-flow.json`。
4. B 端接诊抽屉显示该问诊处方的状态与驳回原因。
5. 驳回即终态：驳回通知引导患者重新发起问诊或挂号（在线问诊单可复用原摘要），不开放改方重提，一对一唯一约束不动。
6. 契约清理：删 `contracts/order-flow.json` 从未接线的 `message_types`/`messages`（DRUG_ORDER_STATUS）。

审核服务与 C 端处方接口是线下来源共用代码，可见性与通知自动覆盖挂号来源处方，不加来源分支。

**B. 问诊后随访**（题目创新点"主动关怀"）：在线问诊 COMPLETED 时由 server-java 同事务 eager 生成一条随访站内消息（`in_app_messages`，文案确定性如"您好些了吗？药吃完了吗？"，disclaimer 兜底），新增 `visible_at` 延迟可见机制——默认完成 3 天后才出现在站内消息通道；`in_app_messages` 加 `related_online_consultation_id` 外键 + `UNIQUE(related_online_consultation_id, type)` 幂等（与 A3 同表同套路）。消息类型、文案、延迟天数入 `contracts/online-consultation.json`。不经 Agent、不读病情内容，与服药打卡的周期性提醒互补。线下接诊随访不做，保持单一来源。

**C. 导诊医生推荐理由**（题目创新点"可解释推荐"）：科室号源卡医生条补确定性推荐理由——server-java `DoctorSlotCard` 透出 `specialty`（doctors 表现有列，零 schema 变更，C 端目录 API 与 Agent 工具两入口同视图自动覆盖）；C 端科室号源卡医生条加"擅长：xxx"行（与挂号目录医生卡同款）；导诊摘要确定性携带最早可约医生的职称+擅长（模板入 `contracts/guided-registration.json`，server-py 编排代码拼装，无擅长时省略子句，LLM 零参与）。明确不做评分/好评率等评价数据（答辩口径"不造评价数据"），不改变有号优先排序。

**Blocked by:** 56 - 在线问诊处方审核与购药（claimed，代码已完成）；50 - 智能导诊科室号源卡（claimed，仅 C 块依赖）

**Status:** claimed

A 块：

- [x] `contracts/prescription-flow.json` 新增审核结果消息类型与文案（APPROVED/REJECTED 各一）；`contracts/order-flow.json` 删除未使用的 `message_types`/`messages`；`ContractsTest` 同步
- [x] schema.sql：`in_app_messages` 加 `related_prescription_id BIGINT REFERENCES prescriptions(id)` + `UNIQUE(related_prescription_id, type)`（幂等 ALTER 区套路；与 B 块的 `related_online_consultation_id`/`visible_at` 同票一并落地，只重建一次）
- [x] server-java：`PrescriptionService.review` 通过/驳回两分支同事务写站内消息（文案从契约加载、disclaimer 兜底、撞唯一约束幂等不冒 500）；service 级单测覆盖两分支与重复审核幂等
- [x] server-java：`GET /api/c/prescriptions` 返回全状态处方（status 及标签从契约加载）；service 单测 + 一条 MockMvc 主链路冒烟
- [x] C 端：处方页按状态渲染（审核中 / 未通过+引导 / 可下单）；问诊完成页加"查看电子处方"出口按状态分流
- [x] B 端：接诊抽屉（OnlineConsultationDrawer）展示该问诊处方状态与驳回原因

B 块：

- [x] `contracts/online-consultation.json` 增补随访段（message_type、文案模板、delay_days 默认 3）；双栈契约测试同步
- [x] schema.sql：`in_app_messages` 加 `visible_at TIMESTAMPTZ`（存量行回填 now()）与 `related_online_consultation_id` 外键 + `UNIQUE(related_online_consultation_id, type)`
- [x] server-java：`OnlineConsultationService.complete` 同事务写随访消息（visible_at = completed_at + delay，撞唯一约束幂等）；service 单测覆盖生成与幂等
- [x] server-java：C 端消息列表查询加 `visible_at <= now()` 过滤（就诊指引卡等即时消息不受影响）；service 单测
- [x] 演示可见性：延迟经配置缩短到立即可见（沿用 `/api/b/demo/**` 或环境配置边界），保证评审现场可演示
- [x] C 端消息页渲染随访卡（文案 + 免责声明标注）

C 块：

- [x] server-java：`DoctorSlotCard` 透出 `specialty`；service 单测断言字段
- [x] 抽查 seed 15 位医生 specialty 文案质量（具体病症向，非"常见病"）；若改 seed.sql 与 schema 变更一并 reset + verify
- [x] C 端 department-slots-card 加"擅长"行（缺值不渲染该行）
- [x] `contracts/guided-registration.json` ok 摘要模板加推荐理由子句；server-py 拼装逻辑与双栈契约测试同步

schema 与验收：

- [x] schema/seed 全部变更完成后 `uv run python scripts/reset_zhiyu.py` + `uv run python scripts/verify_zhiyu.py`
- [ ] 浏览器/开发者工具实测无控制台错误，人工走通：主线"开方 → 审核通过 → 通知 → 处方页下单 → 模拟支付 → B 端确认完成"；支路"驳回 → 通知 → 引导重新问诊"；"完成问诊 →（缩短延迟）→ 消息页出现随访"；"导诊 → 科室号源卡摘要与医生条均见推荐理由"
- [ ] 票单置 done 前：README 依赖图 T60 节点加 `[x]`

## Comments

- 立项会话已同步：CONTEXT.md「电子处方」「站内消息通道」「科室号源卡」「随访」词条、README 依赖图 T60 节点、ADR-0030。票内不再重复这些文档项。
- 施工记录（t60-prescription-loop 分支，worktree `.worktrees/t60-prescription-loop`）：
  - 提交：6e481b4 立项 docs、2fed22f 主实现、2c58757 合并 main（票 61 health_observations，无冲突）、6e89dea 审查修复。
  - 与票面的两处偏差：①抽屉处方端点落在 `GET /api/b/reception/online-consultations/{id}/prescription`——`/api/b/**` 默认仅 admin 放行，接诊医生需走 reception 豁免命名空间；②「撞唯一约束幂等不冒 500」用 `INSERT ... ON CONFLICT DO NOTHING`（InAppMessageMapper.insertIgnoreConflict）实现——code-review 发现 PG 事务内约束违例后事务即 aborted，Java 侧 catch DuplicateKeyException 无法挽救，try/catch 方案对真实 PG 不成立。
  - code-review（Standards/Spec 双轴）：代码本体无硬违规、无 scope creep；两处发现（幂等语义、visible_at 过滤缺单测）均已在 6e89dea 修复并复验。
  - 实测：API 层全流程 PASS（主线开方→审核通过→通知→下单→模拟支付→B端完成；驳回支路；随访立即可见；号源卡 specialty 双入口透出+摘要推荐子句拼接）；admin 浏览器（Playwright）登录/审核页/订单页/医生抽屉处方状态与驳回原因均正常、无控制台错误；证据在 `.scratch/t60-evidence/`（未入库）。修复后三条消息写入路径已紧凑复验 PASS。
  - C 块 seed 抽查：15 位医生 specialty 均为具体病症向，无需改 seed。
  - 云演示库已按合并后 schema 重建并 verify 通过（注意：并行会话也在一起 reset 该库，验收期间被覆盖过一次，演示前如有异样重跑 reset_zhiyu.py 即可）。
  - 待人工：小程序支付宝开发者工具走查（处方页三态、问诊完成页出口、消息页随访卡、号源卡擅长行）——自动实测无法覆盖，走查无问题后可将票置 done 并给 README T60 加 [x]。
  - 新发现问题（建议单独开票）：server-py httpx 默认 trust_env 拾取 Windows 系统代理，导致 server-py→server-java 业务回调被塞进本机代理 502（预问诊科室恒 null、导诊工具全失败）；建议业务回调客户端显式 trust_env=False 或启动脚本设 NO_PROXY。
  - 已修复：`BusinessCallbackClient`（server-py/app/tools/business.py）构造 AsyncClient 时显式 `trust_env=False`，单点覆盖全部业务回调与科室目录通道；本机复现证实默认值会从注册表拾取系统代理（`127.0.0.1:7892`）而显式关闭后零代理挂载；新增 `tests/test_business_client.py` 回归断言（MockTransport 不触网，既有测试测不出该回归），37 项相关测试全绿。

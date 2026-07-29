# 23 — B 端处方安全提醒

**What to build:** B 端开方时的实时安全检查：医生选药过程中，调用票 11 位于 server-java `rule/` 的确定性禁忌能力，校验当前健康档案过敏史 × 药品成分、已选药品之间的相互作用；命中即在开方表单红字警告并阻止提交。可附带由 server-py 生成、带免责声明标注的解释与替代建议，但 LLM 不参与规则判断。

**Blocked by:** 09 — 电子处方；11 — 禁忌检测

**Status:** done

- [x] 开方表单实时调用 server-java 禁忌/相互作用检查接口；患者、档案、处方归属由 server-java 根据已鉴权医生与接诊关系校验
- [x] 红字警告组件（原因 + 建议）
- [ ] 可选 LLM 解释与替代药建议必须展示免责声明标注；替代建议仍需重新经过同一确定性禁忌/相互作用检查后方可展示（可选项，本票不实施，见 Comments）
- [x] 命中禁忌时 server-java 必须拒绝处方提交，不能只依赖前端禁用按钮
- [x] DTO/Entity/View 映射使用 MapStruct，状态与决定值从 `contracts/` 推导
- [x] server-java 规则/service 单测与 MockMvc 覆盖正常、危险、越权、绕过前端直接提交四类分支

## Comments

- 2026-07-29：明确票 23 复用的是 server-java 确定性规则能力；前端提示只是展示层，提交接口必须再次执行同一规则。
- 2026-07-29：完成 B 端处方安全提醒。`POST /api/b/reception/appointments/{id}/contraindication-check` 由已鉴权医生名下挂号单派生患者上下文（取消挂号 409、他人挂号单 404、非医生 403），复用票 11 `ContraindicationService` 确定性规则；`PrescriptionService.create` 提交前强制复跑同一规则，BLOCKED/REVIEW_REQUIRED 均 409 且话术（`blocked_prescription`/`review_required_prescription`）新增入 `contracts/contraindication.json`，双栈兼容。admin 开方表单选药后 300ms 防抖调用检查，命中红字 Alert（原因+建议）并禁用提交，安全时绿色提示（有意的 UX 增强）；检查请求失败时前端放行、服务端 409 兜底。可选 LLM 解释与替代药建议为票面「可附带」项，本票不实施，避免越票；如后续实施须满足该勾选项全部条件。双轴 code-review 无硬违规，采纳修正：`safetyException` 命名、「。：」双标点、checkSafety 补齐取消挂号一致性校验。server-java 189 项、server-py 52 项测试与 spotless/typecheck/admin build 全绿。
- 2026-07-30：完成真实环境端到端验收（本地进程连云数据库）。链路：admin 建当日排班 → C 端 mock 患者建激活档案（过敏史=青霉素）→ 真实 LLM 对话两轮完成挂号（ appointment 事件落单，号源 8→7 原子扣减）→ doctor.lin 工作台可见挂号单。验证分支：检查接口在 Neo4j 禁忌 seed 缺失时按设计 fail closed 返回 REVIEW_REQUIRED（blocked=true）；绕过前端直接提交处方 409（`review_required_prescription` 话术+原因，标点格式正确）；admin 角色 403；他人/不存在挂号单 404。浏览器走查（playwright-core 驱动系统 Edge）：登录 → 接诊台 → 接诊抽屉 → 选阿莫西林 → 红字警告（原因+建议）渲染、提交按钮禁用，控制台零错误、无失败请求。注意：云端 Neo4j 当前无禁忌 seed 数据（查询成功但无节点），SAFE 与过敏命中 BLOCKED 两条路径待人工执行 `deploy/neo4j/seed.cypher` 后复验；云端演示库留存本次验收演示数据（排班 id=1、患者「票23验收患者」及其档案与 1 号挂号单）。

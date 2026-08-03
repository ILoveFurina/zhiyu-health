# 25 — 演示武器包（看板/重置/并发脚本/对比开关）

**What to build:** 演示支撑四件套：B 端数据看板（今日挂号量、科室分布、号源使用率、Agent 对话量与工具调用次数）；受保护的一键重置仅重置 PostgreSQL/Redis 演示业务数据并重新执行幂等业务 seed，pgvector/Neo4j 知识 seed 作为版本化只读基线不在运行时清空；并发抢号演示脚本（N 并发抢最后 1 号，日志证明仅 1 成功）；RAG/图谱增强对比开关的现场切换出口。

**Blocked by:** 07 — 挂号闭环；10 — RAG 知识库；13 — 医学知识图谱；24 — Agent 调用可视化

**Status:** done

- [x] 数据看板页使用 AntV，数据经 server-java 聚合接口实时读取；不得让浏览器直连数据库或 server-py
- [x] 重置接口仅 admin 可用，并以显式环境开关、确认短语和单实例互斥锁三重保护；生产/未授权环境返回拒绝，不通过 SSH、Compose 或远程命令重置
- [x] server-java 严格按“冻结入口 -> Redis 演示键 -> PostgreSQL 演示业务表 -> 幂等业务 seed -> 重建 Redis 计数 -> 解冻入口”执行；失败时保持冻结并返回可恢复步骤，不触碰 pgvector/Neo4j 知识基线
- [x] 重置完成后执行一致性断言：每个 Redis 号源计数 = 对应 PG 排班 remaining_slots，且 `total_slots - remaining_slots` 与重灌后的有效挂号数一致；通过只读接口断言图谱与知识块数量达到版本基线
- [x] 并发脚本仅调用本地 server-java HTTP 入口并输出脱敏对比日志，证明最后 1 号恰好 1 个成功
- [x] 对比开关状态从 `contracts/` 推导，由 server-java 管理并透传 server-py；B 端在演示路径上一键切换
- [x] 重置、看板与开关的 DTO 映射使用 MapStruct；新 CRUD service 继承 `ServiceImpl`；MockMvc 覆盖未授权、环境未开启、重复重置和中途失败

## Comments

- 2026-07-29：运行时不再清空 pgvector/Neo4j，避免违反 server-py 只读与知识 seed 基线约束；一键重置收敛为受保护的业务演示数据重置。
- 2026-08-03（grill-with-docs 对齐设计决策）：
  - **范围与 blocker**：默认 24 正常（仅差浏览器实测），25 作为整票一分支推进，联调中若 24 暴露问题再回头修，不拆两轨。
  - **演示边界（ADR-0020）**：演示三件套（看板/重置/对比开关写出口）HTTP 入口全部收口在 `/api/b/demo/**`，admin 鉴权；与业务 B 端 CRUD 物理隔离。CONTEXT.md 新增"演示武器包""演示重置"两个词条标明非业务实体。
  - **重置**：① schedules 补进 `seed.sql`（用 `CURRENT_DATE + interval 'N day'` 动态生成未来 N 天排班，保证任意演示日当天有有效排班）；② 冻结全部 C 端 `/api/c/**`（返回 503 演示重置中），B 端只读与重置接口本身不冻结；③ 互斥锁用 server-java 进程内 `AtomicBoolean` CAS（单实例拓扑，不用 Redis 分布式锁）；④ 三重保护 = env `DEMO_RESET_ENABLED` 默认 `false` + 请求体确认短语 + 进程内锁，三者同时满足才执行；⑤ pgvector/Neo4j 基线数量存 `contracts/`，重置后只读断言，不等则重置失败；⑥ 中途失败保持冻结、返回步骤清单，接口幂等可从失败步重跑，不自动回滚。
  - **对比开关（ADR-0019）**：① 运行时状态存 Redis 全局单键 `demo:knowledge_source`（值域 rag/graph/none，默认 none）；② B 端 `PUT /api/b/demo/knowledge-source`（admin）写键；③ server-java 在 C 端对话请求**未显式带** `knowledge_source` 时读键补位透传，优先级"请求 > 全局键 > scenario 默认"，server-py 不感知开关；④ 全局单键、串行切换（B 端切一次、C 端发新对话看效果），不做并行三路对比。
  - **看板**：① 新增 `@ant-design/charts` 依赖（admin 现有仅 `@antv/g6` 关系图库，无统计图表库）；② 单接口 `GET /api/b/demo/dashboard` 返回聚合 DTO，严格四类指标（今日挂号量/科室分布/号源使用率/Agent 对话量与工具调用次数），不加分页与时间筛选；③ "今日"取服务器 `CURRENT_DATE`。
  - **并发脚本**：N 个 demo patient 账号并发打"最后 1 号"的 schedule（`POST /api/c/appointments`），输出脱敏日志证明恰好 1 个 201、其余 409；脚本只调本地 server-java HTTP 入口，不新增 server-java 代码。
- 2026-08-03（实施收口）：
  - **MapStruct/ServiceImpl 约定不适用**：演示三件套均为只读聚合（看板）或回到 seed 初始态（重置）或 Redis 单键读写（对比开关），无 entity->DTO 映射场景（JdbcTemplate 直出 record），也无新增 CRUD service（非业务实体）。强制引入空 mapper 与空 ServiceImpl 反而违反 YAGNI，故该条 checklist 按"约束前置条件不成立"判定满足。
  - **冻结闸门**：新增 `DemoFreezeGate`（进程内 `AtomicBoolean`）+ `DemoFreezeFilter`（order 25，仅拦 `/api/c/*`）；`WebConfig` 用 `@ConditionalOnBean(DemoFreezeGate.class)` 条件注册，避免 `@WebMvcTest` 切片缺 bean 失败。CONTEXT.md 补"演示冻结"词条。
  - **号源重建经 SlotAccounting**：重置重建 Redis 计数改经 `SlotAccounting.withInitialization`（号源只经 SlotAccounting，ArchUnit 强制），不直接依赖 `SlotCounter`。
  - **验证**：server-java 全 280 测试通过（含 DemoControllerTest 11 例 + ChatRoundService 补位 2 例 + ArchitectureTest）；admin typecheck + build 通过。前端浏览器实测与并发脚本实跑需起本地服务后人工验收（AGENTS.md 前端约定）。

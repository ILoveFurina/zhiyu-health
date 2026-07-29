# 25 — 演示武器包（看板/重置/并发脚本/对比开关）

**What to build:** 演示支撑四件套：B 端数据看板（今日挂号量、科室分布、号源使用率、Agent 对话量与工具调用次数）；受保护的一键重置仅重置 PostgreSQL/Redis 演示业务数据并重新执行幂等业务 seed，pgvector/Neo4j 知识 seed 作为版本化只读基线不在运行时清空；并发抢号演示脚本（N 并发抢最后 1 号，日志证明仅 1 成功）；RAG/图谱增强对比开关的现场切换出口。

**Blocked by:** 07 — 挂号闭环；10 — RAG 知识库；13 — 医学知识图谱；24 — Agent 调用可视化

**Status:** ready-for-agent

- [ ] 数据看板页使用 AntV，数据经 server-java 聚合接口实时读取；不得让浏览器直连数据库或 server-py
- [ ] 重置接口仅 admin 可用，并以显式环境开关、确认短语和单实例互斥锁三重保护；生产/未授权环境返回拒绝，不通过 SSH、Compose 或远程命令重置
- [ ] server-java 严格按“冻结入口 → Redis 演示键 → PostgreSQL 演示业务表 → 幂等业务 seed → 重建 Redis 计数 → 解冻入口”执行；失败时保持冻结并返回可恢复步骤，不触碰 pgvector/Neo4j 知识基线
- [ ] 重置完成后执行一致性断言：每个 Redis 号源计数 = 对应 PG 排班 remaining_slots，且 `total_slots - remaining_slots` 与重灌后的有效挂号数一致；通过只读接口断言图谱与知识块数量达到版本基线
- [ ] 并发脚本仅调用本地 server-java HTTP 入口并输出脱敏对比日志，证明最后 1 号恰好 1 个成功
- [ ] 对比开关状态从 `contracts/` 推导，由 server-java 管理并透传 server-py；B 端在演示路径上一键切换
- [ ] 重置、看板与开关的 DTO 映射使用 MapStruct；新 CRUD service 继承 `ServiceImpl`；MockMvc 覆盖未授权、环境未开启、重复重置和中途失败

## Comments

- 2026-07-29：运行时不再清空 pgvector/Neo4j，避免违反 server-py 只读与知识 seed 基线约束；一键重置收敛为受保护的业务演示数据重置。

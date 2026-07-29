# 10 — RAG 知识库

**What to build:** RAG 落地：策划 15–20 个症状场景知识数据（LLM 辅助 + 人工校对），经 doubao-embedding-vision 离线生成向量并纳入幂等 seed；server-py 运行时只读 pgvector，`search_knowledge` 工具接入 Agent，导诊回答先检索后生成；检索增强做成可开关（供演示对比裸 LLM vs 知识增强）。

**Blocked by:** 31 — 票 04 拆分迁移

**Status:** ready-for-agent

- [ ] `schema.sql` 增加 knowledge_chunks 表、vector 字段与 pgvector HNSW 索引；表结构仍由统一 schema 管理
- [ ] server-py 提供离线 embedding 生成工具，产出可审查、可版本化的 15–20 场景 seed；工具不得在运行时直写 pgvector，幂等入库由统一 seed 流程完成
- [ ] server-py 的只读知识检索 service + `search_knowledge` 工具接入 Agent 导诊流程；不得读取或写入业务实体
- [ ] 在 `contracts/` 定义知识增强模式；server-java 对话入口按契约向 server-py 透传，票 10 提供可测试的运行时 seam，B 端现场切换出口留给票 25
- [ ] 固定 10 条典型症状查询集及期望知识块，Top-3 命中不少于 8 条方可通过
- [ ] TestClient 使用 fake embedding/检索替身断言“先检索、后生成”及关闭增强时不检索；`ruff`、`mypy`、`lint-imports` 全部通过

## Comments

- 2026-07-29：按双栈硬约束澄清写入边界。embedding 生成属于离线数据准备，server-py 运行时对 pgvector 始终只读；票 10 不是纯 Python 票，还会修改统一 schema/seed 与跨栈契约。

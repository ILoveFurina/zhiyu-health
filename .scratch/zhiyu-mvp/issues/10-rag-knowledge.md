# 10 - RAG 知识库

**What to build:** RAG 落地：策划 50 个症状场景知识数据（LLM 辅助 + 人工校对），经 doubao-embedding-vision 离线生成向量并纳入幂等 seed；server-py 运行时只读 pgvector，`search_knowledge` 工具接入 Agent，导诊回答先检索后生成；知识增强做成可开关（供演示对比裸 LLM vs 知识增强）。

**Blocked by:** 31 - 票 04 拆分迁移

**Status:** ready-for-agent

- [ ] `schema.sql` 增加 knowledge_chunks 表（含 department 列）、vector 字段与 pgvector HNSW cosine 索引；表结构仍由统一 schema 管理。向量维度路径 B：DDL 写死 `vector(1024)` + yml 配置默认值 + 启动校验一致性（维度实际值实现期连 endpoint 探测确认）
- [ ] server-py 提供离线 embedding 生成工具，产出可审查、可版本化的 50 场景 seed（10 科室 × 5 症状，扩充 seed 科室至约 10 个、医生至约 15 个补齐 spec 欠账）；工具不得在运行时直写 pgvector，幂等入库由统一 seed 流程完成
- [ ] server-py 的只读知识检索 service + `search_knowledge` 工具（`tools/knowledge.py`，工具范式）接入 Agent 导诊流程；server-py 运行时直连 pgvector 只读检索（embed query + Top-K + 阈值过滤），召回块以 `【标题·科室】正文` 格式进入 LLM 上下文；不得读取或写入业务实体
- [ ] 在 `contracts/` 定义知识增强模式（`knowledge_source` 二态 rag/graph + 降级）；server-java 对话入口按契约向 server-py 透传，票 10 提供可测试的运行时 seam，B 端现场切换出口留给票 25
- [ ] 知识源选择器模型 Y：默认 rag，非导诊场景（interpretation）默认 none；关闭增强时不注入 search_knowledge 工具（LLM 看不到即不检索，裸 LLM）；检索失败/空召回静默降级走裸 LLM；graph 票 13 接手，未实现视为 unavailable 降级；SSE `knowledge` 元事件暴露状态（source/status/count）
- [ ] 固定 10 条典型症状查询集及期望知识块，Top-3 命中不少于 8 条方可通过（集成测试连真实 PG+pgvector+方舟，query 向量缓存）
- [ ] TestClient 使用 fake embedding/检索替身断言"先检索、后生成"及关闭增强时不检索；fake 断言降级（空召回/失败走裸 LLM、knowledge 事件状态正确）；`ruff`、`mypy`、`lint-imports` 全部通过
- [ ] 免责声明注入不变（token/message 事件带，召回块与 knowledge 元事件不带）
- [ ] 胸痛伴冷汗等红线场景保留在库（红线规则在 server-java 先于一切执行，命中即中断不进检索，轻症仍可检索）

## Grilling 决策记录

1. **回到远端边界（A2，不破例）**：embedding 离线在 server-py、运行时 server-py 只读 pgvector、不修订 ADR-0009。撤销原 A1（embedding/检索全归 server-java + 破例）方案。
2. **工具范式（范式 1）**：search_knowledge 作为 `@tool`，LLM 自主调用，与既有 tools/ 架构一致；关闭增强 = 不注入工具。否决检索前置注入（范式 2）。
3. **知识源选择器模型 Y**：二态 rag/graph + 自动降级，默认 rag；切换 UI 出口归票 25，票 10 只提供 contracts 契约 + 运行时 seam。
4. **向量维度路径 B**：DDL 写死 + yml 默认值 + 启动校验，不破坏 schema.sql 静态纪律。
5. **范围扩至 50 场景**：超票面 15-20，补齐 spec seed 欠账（科室至 10、医生至 15）。
6. **表结构**：保留 department 列衔接导诊；HNSW cosine；一场景一 chunk。
7. **验收**：集成测试固定 rag 态跑，10 条查询 Top-3 命中 ≥8。

## Comments

- 2026-07-29：按双栈硬约束澄清写入边界。embedding 生成属于离线数据准备，server-py 运行时对 pgvector 始终只读；票 10 不是纯 Python 票，还会修改统一 schema/seed 与跨栈契约。
- 2026-07-29（grilling 补充）：经 grill-with-docs 会话确定工具范式、知识源选择器模型 Y、50 场景范围、路径 B 维度管理。详见 ADR-0010。

## 待人工验收项

- 50 场景知识文本需医学人工校对（content 含症状+病因+建议科室+就医提示，非诊断性）。
- `doubao-embedding-vision` 实际维度实现期连 endpoint 探测确认，若非 1024 同步改 DDL 与 yml。
- 切换 UI 出口（票 25）落地后浏览器实测无控制台错误。

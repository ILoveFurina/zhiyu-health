# RAG 知识库检索链路与知识增强模式

Status: accepted

## 背景

票 10 落地 RAG 知识库。ADR-0009 数据访问权边界定"向量检索 = server-py 只读""LLM 调用仅发生在 server-py"，本决策在该边界内落地 RAG，不破例：embedding 离线生成属数据准备（server-py），运行时 server-py 对 pgvector 始终只读，knowledge_chunks 表 schema 仍由统一 schema.sql 管理。

## 决策

1. **knowledge_chunks 表归统一 schema**：表与 pgvector HNSW cosine 索引在 server-java `schema.sql`（HNSW + `vector_cosine_ops`）。保留 `department` 列衔接导诊科室推荐；一场景一 chunk 粒度。向量维度用路径 B：DDL 写死 `vector(1024)`（pgvector 列维度必须为常量），`application.yml` 配置默认值，启动/写入前校验配置维度 == DDL 维度 == endpoint 实际返回，不一致 fail-fast。维度实际值实现期连 endpoint 探测确认。

2. **embedding 离线在 server-py**：server-py 提供离线 embedding 生成工具，读知识文本 seed -> 调 doubao-embedding-vision -> 产出可审查可版本化的向量，纳入幂等 seed 入库。工具不得在运行时直写 pgvector，运行时 server-py 对 knowledge_chunks 只读。

3. **search_knowledge 工具范式（范式 1）**：`search_knowledge` 作为 LangGraph `@tool` 在 server-py `tools/knowledge.py`，server-py 运行时直连 pgvector 只读检索（embed query + Top-K + 阈值过滤）。不作为检索前置注入（范式 2）--让 LLM 在导诊中自主调用，与既有 `recommend_doctors` 等工具架构一致。system prompt 引导"导诊前先检索知识"。红线症状规则在 server-java 先于一切执行，命中即中断不进检索。

4. **知识增强开关 = 是否注入 search_knowledge 工具**：关闭增强时不把 search_knowledge 注入工具集，LLM 看不到它，自然不检索（裸 LLM）。开关经 `contracts/` 定义知识增强模式，server-java 对话入口按契约向 server-py 透传 `knowledge_source`，server-py 据此决定是否注入工具。票 10 提供可测试的运行时 seam；B 端现场切换 UI 出口归票 25。

5. **知识源选择器（模型 Y）**：`knowledge_source` 为二态 rag/graph + 自动降级。默认 rag；非导诊场景（interpretation）默认 none。检索失败或空召回静默降级走裸 LLM；graph 票 13 接手，未实现时视为 unavailable 降级。降级状态经 SSE `knowledge` 元事件暴露（source=用户选择、status=ok/degraded/unavailable、count=N），用户侧不强提示，演示与 B 端 trace 可见。

6. **召回块注入格式**：工具返回的召回块以 `【标题·科室】正文` 结构化纯文本进入 LLM 上下文，让模型能衔接科室推荐（呼应 department 列）。

7. **范围扩至 50 场景**：超票面 15-20，10 科室 × 5 症状；扩充 seed 科室至约 10 个、医生至约 15 个补齐 spec 欠账，让 department 对齐真实科室。胸痛伴冷汗等红线场景保留在库（红线优先拦截，轻症仍可检索）。

8. **免责声明不变**：token/message 事件带免责声明（既有注入逻辑），search_knowledge 工具返回的召回块与 `knowledge` 元事件不带（非 AI 产出）。

9. **验收**：集成测试连真实 PG+pgvector+方舟，10 条典型症状查询 Top-3 命中 ≥8（query 向量缓存）；TestClient 用 fake embedding/检索替身断言"先检索后生成"及关闭增强时不检索。

## 被否决的方案

- **embedding 入库在 server-java、检索全归 server-java（A1 破例）**：违反 ADR-0009"LLM 调用仅 server-py""向量检索 server-py 只读"，需破例修订约束；远端票 10 校正后明确维持原边界，否决。
- **检索前置注入（范式 2）**：在 server-py 检索边界下，工具范式与既有 `tools/` 架构更一致、关闭增强时"不注入工具即不检索"更自然，否决范式 2。
- **schema.sql 模板化、维度从配置注入 DDL（路径 A）**：破坏"schema.sql 纯静态可执行"纪律（硬约束 6），否决，用路径 B。

## 关联

- 不修订 ADR-0009（维持原数据访问权边界）。
- 票 13（图谱 traverse_graph）接手时填充 `graph` 分支，`knowledge_source` 契约已就位。
- 票 25 提供 B 端现场切换 UI 出口。
- 票 50 演示"切换 RAG/图谱增强开关现场对比"依赖本契约 + 票 25 出口。

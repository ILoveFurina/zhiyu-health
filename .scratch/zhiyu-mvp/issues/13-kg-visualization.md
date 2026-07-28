# 13 — 医学知识图谱全量与可视化

**What to build:** Neo4j 图谱五类节点（症状/疾病/科室/药品/禁忌）数据全量 seed；`traverse_graph` 工具接入 Agent（检索时沿边一跳扩展，GraphRAG 雏形）；B 端"医学知识图谱"页：ECharts 力导向图渲染全图，点击节点看详情。

**Blocked by:** 29 — 票 02 业务迁移（Java）；10 — RAG 知识库

**Status:** ready-for-agent

- [ ] 图谱 seed 与知识库场景同源，至少 150 个节点、200 条关系边
- [ ] traverse_graph 工具接入 Agent
- [ ] B 端图谱可视化页（ECharts graph，节点点击详情）
- [ ] 架构守卫：图谱只装知识，业务实体零双写（ADR-0006）
- [ ] 图谱增强可开关（与票 10 的 RAG 开关联动，供裸 LLM vs 知识增强对比演示）

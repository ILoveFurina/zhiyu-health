# 10 — RAG 知识库

**What to build:** RAG 落地：策划 15–20 个症状场景知识数据（LLM 辅助 + 人工校对），经 doubao-embedding-vision 向量化入 pgvector；`search_knowledge` 工具接入 Agent，导诊回答先检索后生成；检索增强做成可开关（供演示对比裸 LLM vs 知识增强）。

**Blocked by:** 04 — Agent 对话主干

**Status:** ready-for-agent

- [ ] knowledge_chunks 表 + pgvector HNSW 索引
- [ ] embedding 入库管道 + 15–20 场景 seed 数据
- [ ] search_knowledge 工具接入 Agent 导诊流程
- [ ] RAG 增强开关（运行时切换）
- [ ] 固定 10 条典型症状查询集及期望知识块，Top-3 命中不少于 8 条方可通过

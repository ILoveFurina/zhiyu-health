# LLM 选型：火山引擎方舟一站式（豆包 doubao-seed-2.1）

Status: accepted（自动档 `triage → low` 的映射被 ADR-0015 取代：普通对话默认关闭思考）

LLM 能力需求有三：对话 + function calling（Agent 业务闭环）、embedding（RAG）、视觉（报告图片解读）。决定用火山引擎方舟一个 API key 全覆盖：

- **对话 / Agent / 视觉解读**：`doubao-seed-2.1-turbo`——官方主打 Coding 与 Agent 工具调用，多模态视觉理解第一梯队，256k 上下文，方舟 API 兼容 OpenAI 协议。
- **RAG 向量化**：`doubao-embedding-vision`——文本/图片统一向量空间的多模态 embedding。

被否决的方案：DeepSeek（API 无 embedding 与视觉模型，需拼第二家，违背"一个 key"原则）；阿里百炼 Qwen / 智谱 GLM（同为一站式合格候选，但团队已有火山引擎资源与倾向）；**医疗垂直大模型**（蚂蚁/讯飞/京医千询等均无公开按量 API，开源替代品需自建 GPU；且本项目 Agent 不做诊断，医学知识由 RAG + 知识图谱接地，安全判断走规则引擎——垂直模型的领域长处用不上，工具调用能力反而可能退化。LLM seam 保证该决策可逆）。

**关键约束**：Seed 2.1 深度思考默认开启（high）。C 端对话页提供用户可选三档（自动 / 快速回答 / 深度思考，映射 `reasoning_effort`），默认自动；导诊演示用自动或快速档以保证响应速度，报告解读等一次性长任务可用深度思考档。自动档 = 后端按场景分配推理强度（导诊对话低、报告解读等高任务高）。

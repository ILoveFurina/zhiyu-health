# 全 Python 单体后端（FastAPI）

Status: accepted（业务承载部分被 ADR-0009 取代：单体拆为 server-java 业务后端 + server-py Agent 层）

项目周期仅两周，且 AI 模块（导诊 Agent、RAG）必须用 Python。为避免 Java/Node 业务后端与 Python AI 服务之间的跨语言集成成本，决定整个后端（C 端 API、B 端 API、AI Agent）用 Python + FastAPI 单体实现，数据存储选型见 ADR-0003。

行业同类平台的常规选型是 Java/Spring，此处是有意偏离：demo 规模下 Python 性能差异无关，消除集成边界、让 AI Coding 单一语言生成优先。

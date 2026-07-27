# Agent 框架：LangChain + LangGraph（团队既定技术栈）

Status: accepted

Agent 编排层采用 LangChain + LangGraph，而非手写 function calling 循环。决策依据：团队既定技术栈，可读性/扩展性诉求由框架结构承载；生态与行业辨识度对评审有利。

考虑过的替代方案：Pydantic AI（类型安全、代码更接近普通 Python，但非团队技术栈）；OpenAI Agents SDK（生态最薄）；手写 FC 循环（工具增多后结构易散乱）。

**已识别的风险与纪律**：

- LangChain 版本 churn 快，AI Coding 语料中新旧 API 混杂——**依赖由 `pyproject.toml` 声明、精确解析版本由提交入库的 `uv.lock` 锁定，AI 生成相关代码时必须对照锁定版本的官方文档**，不接受凭记忆生成。
- 可读性的真正来源是分层而非框架：**tool 函数只做参数校验的薄壳，业务逻辑全部在 service 层**，框架仅负责编排 LLM 与工具的对话循环。
- LLM 调用经 LangChain 的 OpenAI 兼容接口接火山方舟（见 ADR-0004），C 端导诊链路在默认自动档下走低推理强度（场景分配，见 ADR-0004）。

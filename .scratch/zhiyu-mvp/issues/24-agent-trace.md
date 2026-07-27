# 24 — Agent 调用可视化

**What to build:** 让"Agent 在真实办理业务"肉眼可见：小程序对话中实时显示工具调用进度（正在检索知识/查询号源/锁定号源…）；后端记录每轮对话的 tool trace 入 agent_call_logs；B 端"Agent 调用日志"页可查看每轮对话的完整工具调用链。

**Blocked by:** 07 — 挂号闭环

**Status:** ready-for-agent

- [ ] LangGraph 事件流 → SSE → 小程序进度提示
- [ ] tool trace 持久化（agent_call_logs），仅记录脱敏摘要、工具名、参数类型与执行结果，不落症状、报告、处方等患者敏感原文
- [ ] B 端日志页：按会话查看调用链

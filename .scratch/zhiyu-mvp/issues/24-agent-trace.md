# 24 — Agent 调用可视化

**What to build:** 让“Agent 在真实办理业务”肉眼可见：server-py 从 LangGraph 产生结构化工具进度事件，经 server-java SSE 逐跳透传至小程序；server-java 负责脱敏、审计约束与 agent_call_logs 持久化；B 端“Agent 调用日志”页只能经 server-java 查看每轮对话的工具调用链。

**Blocked by:** 07 — 挂号闭环

**Status:** ready-for-agent

- [ ] 在 `contracts/` 定义 trace SSE 事件、阶段和结果枚举；server-py LangGraph 事件 → server-java SSE → 小程序进度提示，全链路从同一契约推导
- [ ] server-java 对 trace 做字段白名单和脱敏后写 agent_call_logs；server-py 不直接写业务库，任何一端都不得记录症状、报告、处方等患者敏感原文
- [ ] server-java 写入失败不得中断主对话流，但必须生成不含敏感数据的可观测错误；service 测试覆盖该降级
- [ ] B 端日志页经 server-java 按会话查看调用链；仅授权角色可见，MockMvc 覆盖未授权和跨患者数据隔离
- [ ] 浏览器与支付宝开发者工具实测工具进度、日志页及失败降级均无控制台错误

## Comments

- 2026-07-29：将含混的“后端记录”收敛为 server-java 唯一持久化，server-py 只产生结构化 trace 事件。

# ASR/TTS 调用不进 agent_call_logs trace

Status: accepted（票 45 语音双向 ASR/TTS）

语音双向（ASR/TTS，票 45）引入两类新调用：端侧录音经 server-java 转发 server-py 调火山 ASR 识别、按需 TTS 合成。决定：这两类调用**不进** `agent_call_logs`（票 24 工具调用日志），仅在 server-java 入口审计记一笔（调用类型 + 参数类型 + 结果码/长度，不记音频与识别/合成文字原文，遵循硬约束 5 脱敏）。

理由：`agent_call_logs` 记的是 LangGraph 工具循环内的工具调用（`tool_start`/`tool_end` 配对，白名单字段无原文列）。ASR/TTS 不在 LangGraph 循环内--ASR 是对话发起前的输入方式、TTS 是对话完成后的输出呈现，都是端侧发起的独立 HTTP 请求，不是 Agent 工具。强行塞进 trace 会污染"工具调用"语义，且要改 `agent_call_logs` 白名单加列、改 `contracts/sse-events.json` 的 trace 事件集合，违反不越票原则。

被否决的方案：**ASR/TTS 也进 trace 方便调试**。代价是要改 schema + 白名单 + contracts，且 ASR/TTS 非 Agent 工具，语义不符；调试可看 server-java 入口脱敏日志。ASR/TTS 音频全程内存流转不持久化（对齐票 12 视觉管道"原始文件处理完即清理"先例）；识别文字一旦作为消息发出，按现有对话消息规则处理（脱敏摘要、trace 不记原文）。

# agent_call_logs 的脱敏与可用性纪律

Status: accepted（票 24 Agent 调用可视化）

Agent 调用日志（`agent_call_logs` 表）的字段集为**白名单**：round_id/conversation_id/patient_id/tool_call_id/tool_name/phase/result/duration_ms/error_code/seq/created_at。**不设任何能装原文的列**（无 input/output/args/payload/input_summary），这是 hard constraint 5（trace 不记录患者敏感原文）的物理实现--不存在的列无法被误写，运行时无需脱敏逻辑。`error_code` 只存契约白名单内的码，非白名单统一记 `TOOL_ERROR_UNKNOWN`，复用 `vision-errors.json` 的同款纪律。

trace 事件落库走**独立可失败路径**，不复用 `ChatRoundPersistence.persistEvent` 的 `@Transactional` 同步事务。`ChatRoundService.forward` 收到 trace 事件时，在独立 try-catch 内调 `agentCallLogService.append`，捕获异常后 `log.warn`（只记 roundId/toolCallId/toolName/phase/异常类名，不记异常 message 以免泄漏 SQL/连接串）并继续主对话流。写入失败不向 C 端下发错误、不写 `chat_rounds.error_code`（那是轮次失败码，trace 落库失败不是轮次失败）。demo 阶段不做熔断。

## 被拒绝的替代方案

- **加 `input_summary` 脱敏摘要列**：提供更多审计上下文，但脱敏逻辑要 server-java 按工具名映射摘要模板，增加维护点且有誊写原文的风险。"不存在的列无法被误写"比"运行时脱敏"更可靠，故拒绝。
- **trace 合进 `persistEvent` 同事务**：保证 trace 与 messages 原子落库。但 `persistEvent` 异常会被 `forward` 的 catch 捕获走 `fail()`，连坐整轮对话标记 FAILED--违反票 24"写入失败不得中断主对话流"硬约束。这是可用性优先于一致性的取舍：trace 是可观测性数据，丢失几条 trace 行远比掐断一轮患者对话可接受。

## Consequences

- trace 写入与 messages 落库是两套路径，未来 reader 看到 `forward` 里 trace 用 try-catch 隔离、messages 走 `persistEvent` 事务时，本 ADR 解释为何不统一。
- `agent_call_logs` 表结构是脱敏的物理边界，加列需先评估是否引入原文载体；任何"加个摘要列方便审计"的提案应先对照本 ADR。
- trace 落库失败只产 `log.warn`，不进任何用户可见通道；若需追踪可观测错误，查 server-java 日志按 `roundId` 过滤。

## RAG 检索词与命中片段例外（query/chunks 不脱敏）

`search_knowledge` 工具的 `query` 参数是 LLM 据患者症状改写的**检索词**（症状词/医学术语，如"头晕 恶心"），`chunks` 是命中的**医学知识库原文片段**（库内已存在的医学知识，非患者输入）。两者都不是患者原文逐字回显，与 `entities`、`summary`、`condition_summary` 等病情归纳/患者实体载体性质不同。为回看 RAG 检索质量（检索词是否合理、命中片段是否相关），`query` 与 `chunks` 不进入 server-py `app/agent/events.py` 的 `_MASK_SENSITIVE_KEYS`，在 `tool_output_summary` 中原样保留；其余健康原文载体仍遮蔽为 `[已脱敏]`。

这是 hard constraint 5 的受控例外，边界明确：

- **不例外**：患者输入原文、图谱实体与病情归纳（entities/summary/condition_summary），仍按硬约束 5 全部遮蔽。
- **例外仅限**：LLM 工具调用入参 `query` 及 RAG 命中的知识库片段 `chunks`，且仅经 `tool_output_summary` 这一 B 端 trace 展示字段呈现，不进患者侧任何通道。

若未来发现 query/chunks 实际承载了患者 PII（如 LLM 把患者原话直接当 query 透传，或知识库片段混入患者原文），应重新评估是否收回本例外。

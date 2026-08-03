# 24 - Agent 调用可视化

**What to build:** 让“Agent 在真实办理业务”肉眼可见：server-py 从 LangGraph 产生结构化工具进度事件，经 server-java SSE 逐跳透传至小程序；server-java 负责脱敏、审计约束与 agent_call_logs 持久化；B 端“Agent 调用日志”页只能经 server-java 查看每轮对话的工具调用链。

**Blocked by:** 07 - 挂号闭环

**Status:** claimed

- [x] 在 `contracts/sse-events.json` 新增 `trace_events` 数组（无序、可穿插），值为 `["tool_start","tool_end"]`；与有序的 `stream_events`（meta/knowledge/token/message/done）并列。`Contracts.SseEvents` record 增 `traceEvents` 字段 + 防御性拷贝。trace 事件名集合必须与 `card_events`/`ai_card_kinds` 严格不相交且不重名 `done`，由 `ContractsConsistencyTest` 加断言钉死
- [x] server-py `runner.py` 的 `astream` 改 `stream_mode=["messages","agent_actions"]`；`agent_actions` 流在工具发起时产 `tool_start`（工具名 + 参数摘要），ToolMessage 返回时产 `tool_end`（结果枚举 success/error/skipped）。同一 ToolMessage 到达时先发 `tool_end` 再发对应卡片事件（保持“工具完成->结果呈现”因果顺序）；不投影成卡片的知识工具（search_knowledge/traverse_graph）只发 `tool_end`，其结果由 knowledge 元事件承担
- [x] `duration_ms` 由 server-java 按 `tool_start`->`tool_end` 墙钟计算，server-py 不背时钟；`tool_call_id`（LangGraph 工具调用 ID）作为 start/end 配对键透传
- [x] server-java 新增 `agent_call_logs` 表（schema.sql，IF NOT EXISTS 风格）：id/round_id FK->chat_rounds CASCADE/conversation_id FK CASCADE/patient_id FK/tool_call_id/tool_name/phase/result/duration_ms/error_code/seq/created_at；CHECK phase IN ('tool_start','tool_end')、result IS NULL OR result IN ('success','error','skipped')。**无任何原文列**（无 input/output/args/payload/input_summary）；`error_code` 只存契约白名单码，非白名单统一记 `TOOL_ERROR_UNKNOWN`
- [x] trace 事件落库走**独立可失败路径**，不复用 `ChatRoundPersistence.persistEvent` 的 `@Transactional` 同步事务。`ChatRoundService.forward` 收到 trace 事件时，在独立 try-catch 内调 `AgentCallLogService.append`，捕获异常后 `log.warn`（只记 roundId/toolCallId/toolName/phase/异常类名，不记异常 message）并继续主对话流。写入失败不向 C 端下发错误、不写 `chat_rounds.error_code`；demo 不做熔断。service 测试覆盖该降级（mock append 抛异常断言主流程仍 emit + markCompleted）
- [x] trace 事件经 `ChatRoundService.Handle.events()` Flux 透传，WebSocket `event` 信封（`ChatWebSocketHandler`）与 HTTP SSE（`ChatController`，降级通道）均能收到；C 端 `chat-stream.js` 的 `dispatchEvent` 加 `tool_start`/`tool_end` 分支，注入 `onToolStart`/`onToolEnd` handler
- [x] C 端对话页输入框上方新增**工具进度状态条**（瞬态，不进 messages 数组）：`tool_start` 显示“正在{中文文案}…”带 loading；`tool_end` 按结果分流--success 短暂显示后清空，error 显示“{工具中文名}失败”，skipped 静默不显示（降级对用户不可见）。工具名->中文文案映射在 `miniprogram/` 本地维护
- [x] B 端新增 `AgentCallLogController`：`GET /api/b/agent-call-logs/conversations`（有 trace 的会话摘要列表）+ `GET /api/b/agent-call-logs?conversation_id={id}`（扁平事件列表）。仅 `admin` 角色，controller 内就地检查 `@RequestAttribute(AuthFilter.ATTR_AUTH_ROLE)` 非 admin 抛 `ApiException(403)`（项目首个角色鉴权接口，YAGNI 不引入注解/切面，未来 rule-of-three 再提取 `@RequireRole`）。不存在的 conversation_id 返回空列表（不 404）。MockMvc 覆盖：无 token 401 / doctor token 403 / admin 查空会话返空列表
- [x] admin 端新增 `AgentTrace` 页面（`pages/AgentTrace/`）：会话列表 -> 调用链明细两级视图，按 round_id + seq 还原顺序，tool_call_id 配对展示 start/end
- [ ] 浏览器与支付宝开发者工具实测工具进度、日志页及失败降级均无控制台错误

## Comments

- 2026-07-29：将含混的“后端记录”收敛为 server-java 唯一持久化，server-py 只产生结构化 trace 事件。
- 2026-08-03：grilling session（grill-with-docs skill）完成 8 项设计决策，已沉淀为可施工 checklist。决策要点：① 两态 tool_start/tool_end + 结果 success/error/skipped，长工具不靠 trace 心跳（vision 由自身结构校验分片流承担）；② trace_events 进 sse-events.json 与 stream_events 并列，trace 不进 messages 表独立写 agent_call_logs；③ server-py 加 agent_actions stream mode，tool_end 先于卡片事件发送；④ agent_call_logs 无原文列白名单 + error_code 走契约白名单（hard constraint 5 物理实现）；⑤ trace 落库独立可失败路径不连坐主对话流（可用性优先于一致性）；⑥ B 端仅 admin 角色，controller 就地检查，不存在会话返空列表；⑦ trace 经 WebSocket event 信封透传 C 端（票原文“SSE”滞后于票 40 的 WS 化），工具名->中文文案映射放 miniprogram 本地；⑧ C 端输入框上方状态条，success 清空/error 显示失败/skipped 静默。词汇表见 `CONTEXT.md`（工具进度事件/Agent 调用日志/工具进度状态条），架构决策见 `docs/adr/0017-agent-call-logs-redaction-and-availability.md`。
- 2026-08-03（实施）：checklist 第 2 项偏差--锁定版 langgraph 1.2.9 的 StreamMode 不含 `agent_actions`（仅 values/updates/checkpoints/tasks/debug/messages/custom）。改为仅用 `messages` 流，由其自身的 `AIMessage.tool_calls`（发起）与 `ToolMessage`（返回）两个天然时刻检测工具边界，与 agent_actions 的 start/end 等价，且不依赖未发布的 stream mode。功能与契约不变（tool_start/tool_end 两态、tool_call_id 配对、tool_end 先于卡片事件、知识工具只发 tool_end）。最后一项（浏览器/支付宝开发者工具实测无控制台错误）待人工验收，暂未勾选。
- 2026-08-03（实施补充）：① tool_start 不携带「参数摘要」--硬约束 5 + ADR-0017 选定"无任何原文列/无运行时脱敏"纪律，参数摘要属脱敏维护点且有誊写原文风险，故 tool_start 只发 {tool_call_id, tool_name}，与 ADR-0017"不存在的列无法被误写"同构（瞬态 SSE 也不带原文）。② error_code 白名单当前为空（无工具错误码来源：server-py tool_end 只产 result 枚举不产 error_code），所有 error 结果统一记 TOOL_ERROR_UNKNOWN，符合 spec"非白名单统一记 TOOL_ERROR_UNKNOWN"；未来工具产码时在 contracts/ 增白名单数组即可激活。③ code-review 发现 ADR-0017 log 字段缺 toolCallId/toolName，已补齐；_classify_tool_result 与 _tool_output 的 JSON 解析重复已抽 _parse_tool_payload 共享。

# 44 - emotion 情感化核心

**What to build:** C 端 Agent 回复携带三档情绪标注（calm/anxious/fearful），由 server-py 在主回复完成后串行非流式 LLM 调用产生（`response_format=json_object`+pydantic 校验，复用视觉管道结构化输出范式），挂 `message` 事件下发，驱动 AI 气泡配色与确定性安抚语；失败降级 calm 不阻塞演示。

**Blocked by:** 31 - 对话主干双栈化；40 - 对话 TTFT 与 WebSocket

**Status:** done

- [x] 新建 `contracts/emotion.json`：`emotions=[calm,anxious,fearful]`、`default=calm`、`soothing_texts` 映射（calm 无、anxious/fearful 各一条）、`_carried_by=message`
- [x] `schema.sql`：`messages` 加 `emotion VARCHAR(16)` 列
- [x] server-py `services/chat.py`：主回复 token 流完成后、`message` 事件发出前，串行非流式 LLM 调用判 emotion（prompt 判断用户消息情绪，`response_format=json_object` + pydantic `EmotionResult(emotion, rationale)` + 2 次重试，复用 `agent/vision/interpreter.py` 范式）；`rationale` 仅调试不下发；失败/超时降级 calm
- [x] server-py `message` 事件 dict 加 `emotion` 字段（`chat.py:125-133`）
- [x] server-java `ChatRoundPersistence.persistEvent`：`message` 事件落库时写 `emotion` 列（对 message 不做白名单，字段自然透传）
- [x] 端侧 `chat-stream.js` `onAssistant`：读 `data.emotion`；`index.axml`/`index.acss`：按 emotion 分支气泡配色（calm 白泡/anxious 暖橙 `#fff4e6`+`#ff8c00`/fearful 暖红 `#fff0f0`+`#e64545`）+ 安抚语（附气泡底部 disclaimer 上方，与回复共用 disclaimer，不单独标注、不进 messages 数组）
- [x] server-py fake LLM 测试：断言 emotion 调用、降级 calm、`message` 事件携带 emotion
- [x] 票 20 验收要求：固定 3 条焦虑表达样例及期望 emotion/UI/安抚文案，三条均通过方可验收

## Comments

### 2026-08-03 - grill-with-docs 设计澄清

原票 20（情感化包）拆为 43/44/45 三票，本票承接原票 20 的"emotion 决定值 + SSE/消息类型 + 焦虑时 UI 气泡变色 + 安抚文案"一项。决策与 checklist 同等约束力：

- **产生方式**：串行二次 LLM 调用（非主回复同调用结构化输出、非规则判断）。主回复 token 流是 chat 体验命脉（票 40 刚做完 TTFT 提速），改 `response_format=json_object` 流式取子字段不可靠；规则判断不满足 PRD"LLM 结构化输出 emotion"要求。详见 ADR-0019。
- **枚举**：三档 `calm/anxious/fearful`（非五档）。五档 concerned/anxious 边界不稳、演示翻车风险高；三档判别稳、UI 映射清晰（平静-白、焦虑-暖橙、恐惧-暖红）。
- **UI 反馈**：安抚语是确定性文案从 `contracts/emotion.json` 取，**不由 LLM 现场生成**（可测、不浪费调用）。
- **挂载**：挂 `message` 事件加字段（不新增独立 SSE 事件）。emotion 是这条回复的属性而非独立事件；server-java 对 `message` 不白名单、字段自然透传，且落 `messages.emotion` 列供历史回看复现情绪色。`sse-events.json` 不动事件名集合，`ContractsConsistencyTest` 不必动。
- **正交红线**：情绪反馈与红线症状正交--红线是 server-java 确定性规则、独立于 emotion（硬约束 2），emotion 只是 UI 反馈，不触发中断。fearful 安抚语里"建议联系医生或拨打 120"是文案引导，不是红线中断。
- **降级**：emotion 判断失败/超时降级 calm，UI 回落默认白泡，不阻塞演示（对齐票 20"失败时降级不阻塞演示"精神）。
- CONTEXT.md 已补"情绪反馈""安抚语"两条术语；新增 ADR-0019（emotion 串行二次调用决策）。

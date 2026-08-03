# 情绪反馈由主回复完成后的串行二次 LLM 调用产生

C 端 Agent 回复需携带三档情绪标注（calm/anxious/fearful）驱动气泡变色与安抚语（票 44）。对话主链路当前是纯 free-text token 流（票 40 刚完成 TTFT 提速），不使用结构化输出。决定：emotion 由 server-py 在主回复 token 流结束、`message` 事件发出之前，发起一次**非流式** LLM 调用，prompt 为判断用户消息情绪，`response_format=json_object` + pydantic 校验 + 2 次重试（复用 `agent/vision/interpreter.py` 已验证的结构化输出范式），产出 `EmotionResult(emotion, rationale)`，`emotion` 挂到 `message` 事件下发，`rationale` 仅调试用不下发；调用失败/超时降级 calm，不阻塞回复。

被否决的方案：

- **主回复同调用结构化输出**（`response_format=json_object` 一次产出 `{emotion, reply_text}`）：要把 doubao 的 JSON 流式 token 边拼边解析才能取出 reply_text 逐字下发，复杂且脆；且 json_object 流式取子字段不可靠。会改动 chat 主链路与票 40 的 TTFT 成果，风险过大。
- **规则/关键词判断**（不调 LLM）：零额外调用、确定性强，但不满足票 20 PRD"LLM 结构化输出 emotion 字段"的明确要求，召回也有限。

代价：完成延迟增加一次 LLM 调用（非首响应，首 token 不受影响，演示可接受）。emotion 挂 `message` 事件而非新增独立 SSE 事件，因其是这条回复的属性而非独立事件；server-java `ChatRoundPersistence.persistEvent` 对 `message` 事件不做白名单、字段自然透传，且落 `messages.emotion` 列供历史回看复现情绪色。

# 模块1：AI 对话（核心模块）

## 业务概述

AI 对话是智愈平台的核心模块：C 端患者在小程序中与 AI 助手"小愈"进行多轮对话，完成智能导诊、健康科普、挂号、查药购药等任务。链路横跨三端：小程序经 WebSocket（降级 HTTP SSE）进入 server-java，server-java 先做确定性红线规则判断，再以 SSE 转发给 server-py 的 LangGraph Agent，Agent 流式产出的 token、工具卡片与最终消息逐跳透传回端侧。红线症状判断、号源/挂号等业务事实全部由 server-java 确定性裁决，LLM 只负责表达与解释。

## 业务流程

一条消息从输入到落库的端到端旅程：

1. **小程序页面**：`pages/chat/index.js` 的 `startRound` 生成 `request_id`（时间戳+随机串，幂等键），插入用户气泡与 AI 占位气泡，调 `createChatChannel().send` 发起轮次。
2. **实时通道**：`utils/chat-stream.js` 一页一条 WebSocket（`/api/c/chat/ws`）；建连后首帧发送 `auth` 信封携带患者 JWT，收到 `authenticated` 后才发送 `chat` 信封。建连/认证失败或中途断流时，以**同一 request_id** 降级走 HTTP `POST /api/c/chat`（SSE 快照，只投影 meta/最终 message/卡片/done，不伪造逐字节奏）。
3. **server-java 入口**：`ChatWebSocketHandler` 或 `ChatController` 只做信封/参数校验（如 `content` 与 `medication_name` 互斥），装配成 `ChatRoundModels.Command` 交给 `ChatRoundService.accept`。
4. **红线规则先于 LLM**：`RedFlagRuleEngine.judge` 对文本做确定性关键词匹配（胸痛伴冷汗、意识障碍、呼吸窘迫等七组规则）；命中则**不调用 Agent**，直接落 `red_flag` 消息并发 `red_flag` SSE 事件结束轮次。
5. **轮次落库**：`ChatRoundPersistence.create` 在事务内建会话、落用户消息、以 `(patient_id, request_id)` 唯一约束创建 `chat_rounds` 行（状态 `ACCEPTED`）；同 request_id 重入只观察既有轮次，实现幂等。
6. **转发 server-py**：`runAgent` 组装请求体（近期上下文、健康档案、effort/scenario、定位、可信科室目录等），`ChatAgentApi` 用 WebClient 以 SSE POST `/api/agent/chat`。
7. **server-py 编排**：`AgentChatService.stream` 按固定阶段执行——推理档位映射 → 科室重试短路 → 知识源选择 → LangGraph 流 → 最终 message → done；`LangGraphAgentRunner` 用 `create_agent` 编译图（工具集+系统提示词），按 `stream_mode="messages"` 流式消费。
8. **工具调用**（可选）：模型决定调用业务工具（如 `create_appointment`），工具经 `BusinessCallbackClient` 带回调令牌 HTTP 回调 server-java 的 `/api/agent/**` controller，server-java 确定性完成业务写入并返回结构化结果；结果经 `tool_to_event` 契约映射投影为卡片 SSE 事件。
9. **逐跳透传与持久化**：server-java 的 `forward` 对每个上游事件先经 `ChatRoundPersistence.persistEvent` 落库（message/卡片挂免责声明、写 `messages` 表），再实时推给端侧；trace 事件（`tool_start`/`tool_end`）走 `AgentCallLogService` 独立可失败路径。
10. **端侧渲染**：小程序按事件名分发——token 逐字追加、卡片事件追加卡片组件、`message` 定格气泡（emotion 配色+安抚语）、`done` 解锁输入、`red_flag` 替换为警示气泡并置顶横幅。
11. **断连语义**：客户端断连只移除实时订阅者，已接受的轮次继续运行至完成并持久化（ADR-0014）；用户重进页面从对话记录恢复，绝不自动重放。

## 代码地图

| 层 | 职责 | 文件路径 |
|---|---|---|
| 小程序-页面 | 对话页面、气泡状态、卡片分发、红线横幅 | `miniprogram/pages/chat/index.js` |
| 小程序-通道 | 一页一条 WebSocket、auth 首帧鉴权、SSE 降级与快照投影 | `miniprogram/utils/chat-stream.js` |
| 小程序-气泡 | AI 气泡瞬态（等待文案/思考/工具进度），思考内容只留内存 | `miniprogram/utils/ai-bubble-state.js` |
| server-java-controller | HTTP SSE 入口，只做校验与装配 | `server-java/src/main/java/com/zhiyu/health/controller/patient/chat/ChatController.java` |
| server-java-controller | WebSocket 传输适配器，只收发契约信封 | `server-java/src/main/java/com/zhiyu/health/controller/patient/chat/ChatWebSocketHandler.java` |
| server-java-service | 轮次主干：幂等接受、规则前置、Agent 订阅、事件转发 | `server-java/src/main/java/com/zhiyu/health/service/chat/ChatRoundService.java` |
| server-java-service | HTTP SSE 薄适配器（emitter 生命周期） | `server-java/src/main/java/com/zhiyu/health/service/chat/ChatService.java` |
| server-java-service | 轮次与消息的 PostgreSQL 一致性边界、出口免责声明兜底 | `server-java/src/main/java/com/zhiyu/health/service/chat/ChatRoundPersistence.java` |
| server-java-service | 工具 trace 落库与 B 端查询（独立可失败路径） | `server-java/src/main/java/com/zhiyu/health/service/chat/AgentCallLogService.java` |
| server-java-rule | 红线症状确定性规则引擎 | `server-java/src/main/java/com/zhiyu/health/rule/RedFlagRuleEngine.java` |
| server-java-agentclient | server-py 能力门面与对话 SSE 调用 | `server-java/src/main/java/com/zhiyu/health/agentclient/AgentClient.java`、`.../agentclient/ChatAgentApi.java` |
| server-java-controller | Agent 业务工具回调承接（挂号/药品/科室等） | `server-java/src/main/java/com/zhiyu/health/controller/agent/AppointmentToolController.java` 等 |
| server-py-api | Agent 对话 HTTP/SSE 入口（校验回调令牌） | `server-py/app/api/agent.py` |
| server-py-api | SSE 帧拼装与流生命周期日志 | `server-py/app/api/sse.py` |
| server-py-services | 一轮对话的总编排（固定阶段） | `server-py/app/services/chat.py` |
| server-py-services | 推理档位映射（ADR-0015） | `server-py/app/services/reasoning.py` |
| server-py-services | 编排结果→SSE 事件投影、最终 message 组装 | `server-py/app/services/chat_events.py` |
| server-py-agent | LangGraph 装配与流式执行、工具集/提示词选择 | `server-py/app/agent/runner.py` |
| server-py-agent | 模型/工具消息→项目事件、trace 脱敏 | `server-py/app/agent/events.py` |
| server-py-agent | 系统提示词（"小愈"人设与安全纪律） | `server-py/app/agent/prompts.py` |
| server-py-tools | 业务工具 @tool 定义点 | `server-py/app/tools/business.py`、`server-py/app/tools/department.py` |
| server-py-tools | server-java 鉴权回调通道与失败降级 | `server-py/app/tools/callback.py` |
| server-py-装配 | 生产依赖构建（工具注入 runner） | `server-py/app/bootstrap.py` |

## 核心代码走读

### 1.1 端侧发起轮次与事件分发

`miniprogram/pages/chat/index.js:196-240`：`startRound` 生成幂等 `requestId`，把全部 SSE 事件映射为页内 handler——token 追加正文、卡片事件 `appendCard`、红线走独立渲染。

```js
    if (!this._chatChannel) this._chatChannel = createChatChannel()
    this._chatChannel.send({
      requestId: `${Date.now()}-${Math.random().toString(36).slice(2, 12)}`,
      content,
      conversationId: this.data.conversationId,
      effort: GEARS[this.data.gearIndex].key,
      scenario: scenarioFor(content),
      longitude: location && location.longitude,
      latitude: location && location.latitude,
      retryStandardDepartmentId: options && options.retryStandardDepartmentId,
      prescriptionId: options && options.prescriptionId,
      handlers: {
        onMeta: (data) => {
          this.setData({ conversationId: data.conversation_id || this.data.conversationId })
          this._aiBubbleState.onMeta(aiMsg.id, data)
        },
```

`miniprogram/utils/chat-stream.js:31-34`：JWT 不进 URL/Upgrade header（支付宝容器会给 header 值包字面双引号、隧道会剥自定义头），改在连接建立后的首帧 `auth` 携带：

```js
    my.sendSocketMessage({
      data: JSON.stringify({ type: 'auth', data: { token: getToken() } }),
      fail: (detail) => onError(detail),
    })
```

同文件 `chat-stream.js:145-152` 的降级逻辑：WebSocket 任何阶段失败，都以**同一 request_id** 走 HTTP SSE 恢复观察通道——因为服务端轮次按 request_id 幂等，这只是换一个"观察者"，不会重放 Agent：

```js
  function fallbackCurrent() {
    if (!current || current.fallbackStarted) return
    current.fallbackStarted = true
    if (current.handlers.onFallback) current.handlers.onFallback()
    // isAlive 闭包钉住本轮对象：响应迟到时不得更新已结束轮次或已卸载页面。
    const round = current
    streamSse(round, () => current === round)
  }
```

### 1.2 红线规则先于 LLM（server-java）

`server-java/src/main/java/com/zhiyu/health/service/chat/ChatRoundService.java:91-109`：轮次接受时，红线判断在**轮次落库之后、Agent 调用之前**执行，命中即走 `runRedFlag` 分支，`runAgent` 根本不会触发：

```java
        // 红线规则先于轮次接受和 Agent 调用执行；规则结果只在本轮新建时计算一次。
        RedFlagHit redFlag = redFlagRules.judge(command.content());
        ChatRound round = persistence.create(
                command.patientId(),
                command.requestId(),
                preconsultDraft != null ? preconsultDraft.getConversationId() : command.conversationId(),
                command.content());
        RunningRound runtime = new RunningRound(round);
        running.put(round.getId(), runtime);
        if (preconsultDraft != null && preconsultDraft.getConversationId() == null) {
            // 预问诊首轮惰性建会话：回填草稿关联；此后删除会话只置空关联，草稿保留。
            preconsultationService.attachConversation(preconsultDraft.getId(), round.getConversationId());
        }
        if (redFlag != null) {
            runRedFlag(runtime, redFlag);
        } else {
            runAgent(runtime, command, preconsultDraft);
        }
        return runtime.handle();
```

`server-java/src/main/java/com/zhiyu/health/rule/RedFlagRuleEngine.java:12-29`：规则是"词组与"结构——每条规则由一个或多个词组构成，所有词组都至少命中一个同义词才算触发（如"胸痛"且"冷汗"同时出现）。纯关键词匹配、零模型参与，这就是"确定性规则必须先于 LLM"的落地：

```java
    private static final List<Rule> RULES = List.of(
            new Rule("胸痛伴冷汗（疑似心梗）", List.of(List.of("胸痛", "胸口痛", "胸口疼"), List.of("冷汗", "出冷汗", "大汗淋漓"))),
            new Rule("意识障碍", List.of(List.of("意识模糊", "昏迷", "失去意识", "昏厥", "叫不醒"))),
            new Rule("呼吸窘迫", List.of(List.of("呼吸困难", "喘不上气", "无法呼吸", "窒息"))),
            new Rule("中风征兆", List.of(List.of("口角歪斜", "半身不遂", "一侧肢体无力", "半边身子无力", "偏瘫"))),
            new Rule("大出血/呕血咯血", List.of(List.of("大出血", "呕血", "吐血", "咯血", "便血不止"))),
            new Rule("持续抽搐", List.of(List.of("抽搐不止", "持续抽搐", "全身抽搐", "抽搐停不下"))),
            new Rule("急性中毒", List.of(List.of("服毒", "农药中毒", "喝了农药", "服了农药", "误服农药", "煤气中毒", "一氧化碳中毒"))));

    public RedFlagHit judge(String text) {
        String compact = text.replaceAll("\\s+", "");
        return RULES.stream()
                .filter(rule ->
                        rule.groups().stream().allMatch(group -> group.stream().anyMatch(compact::contains)))
                .findFirst()
                .map(rule -> new RedFlagHit(rule.name(), ADVICE))
                .orElse(null);
    }
```

端侧 `miniprogram/pages/chat/index.js:535-546` 收到 `red_flag` 事件后把 AI 气泡替换为警示气泡并置顶横幅：

```js
  showRedFlag(id, data) {
    this._aiBubbleState.fail(id)
    this.patchMessage(id, () => ({
      id,
      role: 'assistant',
      kind: 'red_flag',
      content: data.content,
      disclaimer: '',
      streaming: false,
    }))
    this.setData({ redFlag: data })
  },
```

### 1.3 server-java → server-py：SSE 中继与持久化

`server-java/src/main/java/com/zhiyu/health/agentclient/ChatAgentApi.java:18-26`：对话能力就是一个 SSE POST，返回 Reactor `Flux<ServerSentEvent<String>>`：

```java
    Flux<ServerSentEvent<String>> chat(Map<String, Object> requestBody) {
        return webClient
                .post()
                .uri("/api/agent/chat")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }
```

`ChatRoundService.java:277-308` 的 `forward` 是中继核心：每个上游事件**先持久化、后实时下发**。注意三类特殊处理——`thinking` 只做实时中继不落库（可能复述患者症状，硬约束 5）；trace 事件走独立可失败路径；`done` 到达才标记轮次完成：

```java
            // high 档思考增量只做实时中继：内容可能复述患者症状，禁止进入 messages
            // 与 agent_call_logs；直播结束后即丢弃（票 70，硬约束 5）。
            if (contracts.chatRealtime().thinkingEvent().equals(incoming.event())) {
                runtime.emit(incoming.event(), raw);
                return;
            }
            // 工具进度事件（票 24）：trace 落库走独立可失败路径，不复用 persistEvent 同步事务。
            // 写入失败只 log.warn 不连坐主对话流（ADR-0017：可用性优先于一致性）。
            if (contracts.sseEvents().isTraceEvent(incoming.event())) {
                persistTraceSafely(runtime.round, incoming.event(), raw);
                runtime.emit(incoming.event(), raw);
                return;
            }
            JsonNode data = persistence.persistEvent(runtime.round, incoming.event(), raw);
            if (contracts.sseEvents().doneEvent().equals(incoming.event())) {
                runtime.sawDone.set(true);
                persistence.markCompleted(runtime.round.getId());
                runtime.emit(incoming.event(), data);
                runtime.finish();
```

`server-java/src/main/java/com/zhiyu/health/service/chat/ChatRoundPersistence.java:75-91`：`persistEvent` 在**同一事务**内完成出口免责声明兜底（`disclaimers.mount(object)`）与消息落库——这是"server-py 生成时注入、server-java 出口兜底"双保险中兜底的一环：

```java
            if (sse.metaEvent().equals(eventName)) {
                object.put("conversation_id", round.getConversationId());
            } else if (sse.messageEvent().equals(eventName)) {
                disclaimers.mount(object);
                // 票 44：emotion 由 server-py 串行二次 LLM 调用产生挂 message 事件，
                // 落 messages.emotion 列供历史回看复现情绪色；字段自然透传（对 message 不做白名单），
                // 脏值兜底由 schema.sql 的 CHECK 约束在 DB 层拦截。
                String emotion = nullableText(object.get("emotion"));
                Message saved = conversations.appendMessage(
                        round.getConversationId(),
                        "assistant",
                        object.path("content").asText(),
                        Message.KIND_TEXT,
                        nullableText(object.get("effort")),
                        null,
                        emotion);
                object.put("message_id", saved.getId());
```

### 1.4 server-py 编排：档位、LangGraph 与事件投影

`server-py/app/services/chat.py:82-130`：`stream` 方法头部注释即阶段清单，读这一个方法就能看到一轮对话的全貌：

```python
        # 1. auto 只在编排层解释，传给模型和 meta 的始终是确定档位。
        effort = map_reasoning_effort(effort_choice, scenario)
        yield {"event": EVENT_META, "data": {"effort": effort}}

        # 2. 卡片点选/失败重试携带可信科室 ID，直接查询且不再进入 Agent。
        if retry_standard_department_id is not None:
            async for event in self._guidance.stream_slots(
                retry_standard_department_id, effort, longitude, latitude
            ):
                yield event
            return

        # 3. 明确挂号/导诊请求只读取可信目录，不增加独立 LLM judge；普通请求零目录调用。
        department_plan = await self._department_tool_policy.resolve(
            messages, scenario, longitude, latitude
        )
```

`server-py/app/services/reasoning.py:24-29`：推理档位映射（ADR-0015）——`auto` 在编排层就翻译成 `disabled`/`high`，模型永远只收到确定档位；普通对话默认关闭思考是为 TTFT 优化（实测关闭思考首正文约 1.4–1.8 秒）：

```python
def map_reasoning_effort(choice: EffortChoice, scenario: Scenario) -> ReasoningEffort:
    if choice == "quick":
        return "disabled"
    if choice == "deep":
        return "high"
    return _AUTO_BY_SCENARIO[scenario]
```

`server-py/app/agent/runner.py:164-174`：runner 只执行 LangGraph，按 `stream_mode="messages"` 消费消息流，工具调用边界由 `AIMessage.tool_calls`（发起）与 `ToolMessage`（返回）两个天然时刻检测：

```python
    async def astream_reply(
        self, messages: list[dict[str, str]], effort: ReasoningEffort, context: AgentContext
    ) -> AsyncIterator[AgentOutput]:
        graph = self._graph(effort, context.knowledge_source, context.scenario)
        lc_messages = _to_lc_messages(messages, context)
        if context.department_tool_choice == "suggest_standard_departments":
            async for output in _astream_graph_updates(graph, lc_messages, context, effort):
                yield output
            return
        async for output in _astream_graph_messages(graph, lc_messages, context, effort):
            yield output
```

`server-py/app/services/chat_events.py:19-33`：runner 的输出在**唯一投影点**变成契约 SSE 事件——token 累积进 `parts`、卡片类 dict 负载统一注入免责声明、trace 与知识事实不附声明：

```python
def project_agent_output(
    output: AgentOutput, parts: list[str], disclaimer: str
) -> dict[str, object] | None:
    if output.event == EVENT_TOKEN and isinstance(output.data, str):
        parts.append(output.data)
        return {"event": EVENT_TOKEN, "data": {"text": output.data}}
    if output.event == EVENT_THINKING and isinstance(output.data, str):
        return {"event": EVENT_THINKING, "data": output.data}
    if output.event == EVENT_KNOWLEDGE and isinstance(output.data, dict):
        return {"event": EVENT_KNOWLEDGE, "data": output.data}
    if output.event in (EVENT_TOOL_START, EVENT_TOOL_END):
        return {"event": output.event, "data": output.data}
    if isinstance(output.data, dict):
        return {"event": output.event, "data": {**output.data, "disclaimer": disclaimer}}
    return None
```

### 1.5 工具调用链：@tool → runner 注册 → 鉴权回调 → server-java 承接

这是本模块最有教学价值的一条横切链，分四跳：

**第一跳·@tool 定义点**（`server-py/app/tools/business.py:61-96`）：`build_business_tools` 内用 LangChain `@tool` 装饰器定义业务工具。关键点：患者/会话身份**不作为模型可见参数**，而是从可信运行时上下文 `ToolRuntime[AgentContext]` 取——模型无法臆造 patient_id 去查别人的挂号：

```python
    @tool
    async def create_appointment(
        schedule_id: int,
        condition_summary: str,
        runtime: ToolRuntime[AgentContext],
    ) -> dict[str, Any] | str:
        """为当前患者预约所选排班；成功后自动保存本次会话的病情摘要。"""
        args_error = _appointment_args_error(schedule_id, condition_summary)
        if args_error is not None:
            return args_error
        return await forward_post(
            client,
            "/api/agent/appointments",
            {
                "patient_id": runtime.context.patient_id,
                "conversation_id": runtime.context.conversation_id,
                "schedule_id": schedule_id,
                "condition_summary": condition_summary.strip(),
            },
            action="预约挂号",
        )
```

同文件 `business.py:154-162` 返回工具清单；科室类工具（`get_standard_department_slots`、`suggest_standard_departments`）在相邻的 `server-py/app/tools/department.py:148-173` 以同模式定义。注意工具的设计哲学：模型臆造参数属"正常运行时结果"，返回可解释错误文本让模型改正，而不是抛异常掐断 SSE。

**第二跳·runner 注册**（`server-py/app/bootstrap.py:35-45` + `runner.py:134-144`）：生产装配在 `bootstrap.py` 完成——工具清单注入 `LazySettingsAgentRunner`；runner 的 `_tools_for` 按场景/知识源动态选工具集（预问诊场景隔离全部业务工具，隔离由编排代码保证而非提示词）：

```python
    business_client = BusinessCallbackClient(
        settings.server_java_base_url, callback_secret=settings.agent_callback_secret
    )
    knowledge_retriever = build_knowledge_retriever(settings)
    graph_traverser = build_graph_traverser(clients)
    directory = CallbackDepartmentDirectory(business_client)
    runner = LazySettingsAgentRunner(
        [*build_business_tools(business_client), *build_department_tools(directory)],
        knowledge_retriever,
        graph_traverser,
    )
```

```python
    def _tools_for(self, knowledge_source: str, scenario: str) -> list[BaseTool]:
        # 工具隔离：预问诊场景不暴露任何业务工具（医生推荐/号源/挂号），
        # 隔离由编排代码保证而非提示词；知识工具仍按 knowledge_source 注入。
        # rag 态注入 search_knowledge；graph 态注入 traverse_graph（互斥）；
        # none/其他不注入（LLM 看不到即不检索）
        base = [] if scenario == _PRECONSULT_SCENARIO else self._base_tools
        if knowledge_source == "rag" and self._knowledge_tools:
            return [*base, *self._knowledge_tools]
        if knowledge_source == "graph" and self._graph_tools:
            return [*base, *self._graph_tools]
        return list(base)
```

**第三跳·鉴权回调**（`server-py/app/tools/callback.py:13-28`）：`BusinessCallbackClient` 在每次请求头携带 `X-Agent-Callback-Token` 共享密钥，`trust_env=False` 绕过本机代理；`forward_get/forward_post` 把网络失败/业务拒绝降级成可读文本（`callback.py:45-58` 的 `callback_error_text`），避免异常穿透 LangGraph 掐断流：

```python
class BusinessCallbackClient:
    def __init__(
        self,
        base_url: str,
        timeout: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
        callback_secret: str = "",
    ) -> None:
        # server-java 是内网直连目标；绕过系统代理可避免本机代理把回调变成 502。
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout,
            transport=transport,
            trust_env=False,
            headers={"X-Agent-Callback-Token": callback_secret} if callback_secret else None,
        )
```

对称地，server-java 侧 `agentclient/AgentClient.java:37-39` 调 server-py 也带同一令牌头；server-py 入口 `app/api/deps.py:9-23` 用 `secrets.compare_digest` 校验，构成双向内网认证。

**第四跳·server-java 承接**（`server-java/src/main/java/com/zhiyu/health/controller/agent/AppointmentToolController.java:35-39`）：`/api/agent/**` 一族 controller（`AppointmentToolController`、`DoctorRecommendationController`、`MedicationToolController`、`StandardDepartmentToolController` 等）承接工具回调，只做参数校验后委托 service 完成确定性业务写入，返回的卡片视图再沿 SSE 链路投影给端侧：

```java
    @PostMapping
    public AppointmentCard create(@Valid @RequestBody CreateAppointmentRequest request) {
        return toCard(appointmentService.createWithSummary(
                request.patientId(), request.conversationId(), request.scheduleId(), request.conditionSummary()));
    }
```

工具结果回到 server-py 后，`server-py/app/agent/events.py:161-165` 经契约 `tool_to_event` 映射决定它投影成哪个卡片事件（如 `create_appointment → appointment`），未登记的工具不产卡片：

```python
def _tool_event(tool_name: str | None) -> CardEvent | None:
    if tool_name is None:
        return None
    event = get_contracts().sse_events.tool_to_event.get(tool_name)
    return cast(CardEvent, event) if event is not None else None
```

### 1.6 最终消息：情绪判定与免责声明

`server-py/app/services/chat_events.py:36-59`：所有正文 token 流完后，`build_message_data` 串行做第二次 LLM 调用判定情绪（ADR-0019），组装最终 `message` 事件——免责声明在此注入（生成时注入的第一道保险），情绪与安抚语一并下发：

```python
    emotion = await emotion_judge.judge(last_user_text)
    data: dict[str, object] = {
        "role": "assistant",
        "content": "".join(parts),
        "disclaimer": disclaimer,
        "effort": effort,
        "emotion": emotion.emotion,
    }
    soothing = emotion_soothing_text(emotion.emotion)
    if soothing is not None:
        data["soothing_text"] = soothing
    return data
```

`server-py/app/api/sse.py:28-31`：SSE 帧格式本身就是跨栈契约（server-java 按 `event:`/`data:` 行序解析），由测试钉死：

```python
def sse_frame(event: str, data: object) -> str:
    # 帧格式是跨栈契约（server-java 按 event:/data: 行序解析），由 tests/test_sse_logging.py 钉死
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n"
```

至此完成闭环：端侧 `onAssistant` 定格气泡（emotion 配色），`onDone` 解锁输入，server-java 已把消息落库、轮次标记 `COMPLETED`，历史回看与 B 端 trace 均可复现本轮。

## 契约与 ADR

- `contracts/sse-events.json`：SSE 事件协议单一事实源——`stream_events`（meta/knowledge/token/message/done）、`red_flag` 事件、卡片事件清单、`tool_to_event` 工具名→事件名映射、`trace_events`（tool_start/tool_end）与 `message_kinds`（messages 表 CHECK 约束依据）。
- `contracts/chat-realtime.json`：WebSocket 信封协议（auth/authenticated/chat/accepted/event/error）与轮次状态机 `ACCEPTED/RUNNING/COMPLETED/FAILED`；`thinking` 事件只透传不落库；`chat_optional_fields` 定义 chat 信封可选字段。
- `contracts/chat-defaults.json`：effort/scenario 缺省值与枚举（`effort_default: auto`、`scenario_default: triage`）及经纬度校验范围。
- `contracts/disclaimer.json`：免责声明文案"仅供参考，不替代医生诊断"（硬约束 1），server-py 生成时注入、server-java 出口兜底。
- `docs/adr/0014-chat-round-survives-client-disconnect.md`（已接受的对话轮次不随客户端断连取消）：轮次是 PostgreSQL 一等实体，断连只移除观察者，不取消、不重放。
- `docs/adr/0015-default-chat-disables-model-thinking.md`（普通对话默认关闭模型思考）：auto 档按场景映射，普通对话关闭思考换 TTFT，安全边界仍由规则引擎承担。
- `docs/adr/0027-ai-registration-assistant-boundary.md`（AI 挂号助手：AI 只确定标准科室，号源与挂号保持确定性）：Agent 只决定调用时机与科室名称，医院/医生/余号/挂号全部由 server-java 确定性处理。
- `docs/adr/0017-agent-call-logs-redaction-and-availability.md`（Agent 调用日志的脱敏与可用性）：trace 落库走独立可失败路径，可用性优先于一致性，不记患者敏感原文。

## 讲解提示

- **强调"确定性先于概率性"的分层**：红线规则（`RedFlagRuleEngine`）是纯关键词匹配，先于任何 LLM 调用执行；工具回调节点由 server-java 确定性裁决业务事实。LLM 在系统里只负责"表达与解释"，这是医疗场景最重要的架构纪律，也是提示词（`prompts.py`）开篇就写明的安全纪律。
- **学生常问：为什么断网后重进还能看到完整回复？** 答案要点：轮次是一等数据库实体（`chat_rounds`），`(patient_id, request_id)` 唯一约束 + 幂等接受；WebSocket/SSE 只是"观察通道"，断连只 `dispose` 订阅者，上游 Agent 订阅不受影响（ADR-0014）；同 request_id 的 SSE 降级也只是换观察者。
- **学生常问：模型会不会乱调工具、乱填 patient_id？** 答案要点：患者/会话/定位身份在 `AgentContext` 可信运行时上下文里，不是模型可见参数（`business.py` 工具签名里的 `runtime: ToolRuntime[AgentContext]`）；臆造的业务参数（如无效 `schedule_id`）被工具层拦截成可解释文本；真正的归属校验在 server-java service 层兜底。
- **演示建议**：课堂上可用一条"胸痛、出冷汗"消息现场触发 `red_flag` 事件（不发 LLM 请求，秒回警示），再用"我最近咳嗽挂什么科"演示工具链 trace（`tool_start`/`tool_end` 气泡进度 + B 端 Agent 调用日志页），两条演示分别对应"规则先于模型"与"工具调用四跳"两个教学点。

> 返回目录：[docs/textbook/README.md](./README.md)

# 模块13：Agent 可观测与情绪识别

## 业务概述

C 端对话的每一轮 Agent 回复都携带一个三档情绪标注（calm / anxious / fearful），用于驱动小程序 AI 气泡的配色与安抚语；同时，Agent 调用工具的每一次发起与返回都会以 tool_start / tool_end 事件落库，供 B 端管理员在「Agent 调用日志」页面回放调用链。本模块的两条线索都遵循同一条纪律（硬约束 5）：LLM 系统的审计与 trace 只记录脱敏摘要、工具名、参数类型与结果，绝不记录患者敏感原文。情绪判断失败一律降级 calm 不阻塞回复，trace 落库失败只告警不连坐主对话流——两者都是「非关键路径不得绑架关键路径」的典型设计。

## 业务流程

1. 患者在小程序发送消息，server-java `ChatRoundService` 开启一轮对话，经 SSE 转发给 server-py。
2. server-py `runner.py` 驱动 LangGraph 流式产出 token；期间模型发起工具调用时，`agent/events.py` 把 LangGraph 消息投影成 tool_start / tool_end 事件，tool_end 附带**已脱敏、已截断**的工具响应摘要。
3. server-py `api/sse.py` 逐帧编码 SSE 文本并输出 start/frame/complete/cancel/error 日志——只记身份、事件名、字节数与计数。
4. server-java 中继 SSE 事件给小程序；遇到 trace 事件时由 `persistTraceSafely` 在独立 try-catch 内调用 `AgentCallLogService.append` 落库 `agent_call_logs`（tool_start/tool_end 按 tool_call_id 配对计算 duration_ms）。
5. 主回复 token 流结束后、`message` 事件发出前，server-py 发起一次**串行二次非流式 LLM 调用**（情绪 judge），用 `json_object` 模式 + pydantic 校验 + 最多 2 次重试判断用户最新一条消息的情绪；失败/超时/校验不通过一律降级 calm。
6. `message` 事件携带 `emotion`（及 anxious/fearful 时的 `soothing_text`）下发，小程序据此渲染气泡配色与安抚语；server-java 将 emotion 落入 `messages.emotion` 列供历史回看。
7. B 端管理员打开 admin「Agent 调用日志」页：先拉会话摘要列表（可按患者昵称筛选），再点开某个会话查看按 round_id + seq 还原的工具调用链，tool_end 行可展开查看脱敏响应摘要。

## 代码地图

| 层 | 职责 | 文件路径 |
| --- | --- | --- |
| server-py agent | 情绪 judge：串行二次 LLM 调用，失败降级 calm | `server-py/app/agent/emotion.py` |
| server-py services | 主回复完成后组装 message 负载并挂 emotion | `server-py/app/services/chat_events.py` |
| server-py agent | LangGraph 消息 → SSE 事件投影；trace 脱敏与截断 | `server-py/app/agent/events.py` |
| server-py api | SSE 帧拼装与流生命周期日志（不记原文） | `server-py/app/api/sse.py` |
| server-py tools | 业务工具定义与 server-java 鉴权回调通道 | `server-py/app/tools/business.py`、`server-py/app/tools/callback.py` |
| server-java service | trace 事件落库、start/end 配对计时、B 端查询 | `server-java/src/main/java/com/zhiyu/health/service/chat/AgentCallLogService.java` |
| server-java service | 对话轮次编排；trace 落库独立可失败路径 | `server-java/src/main/java/com/zhiyu/health/service/chat/ChatRoundService.java` |
| server-java controller | B 端调用日志只读接口（仅 admin 角色） | `server-java/src/main/java/com/zhiyu/health/controller/staff/chat/AgentCallLogController.java` |
| admin service | 调用日志 API 封装与 TS 类型 | `admin/src/services/agentTrace.ts` |
| admin page | 会话列表 + 调用链时间线两级视图 | `admin/src/pages/AgentTrace/index.tsx` |
| 契约 | 情绪枚举/默认值/安抚语单一事实源 | `contracts/emotion.json` |
| 契约 | tool_start/tool_end 事件与结果枚举 | `contracts/sse-events.json` |

## 核心代码走读

### 13.1 情绪 judge：串行二次 LLM 调用

情绪判断发生在主回复 token 流结束之后、`message` 事件发出之前——它是一次**额外的、串行的、非流式** LLM 调用，与主回复链路完全解耦（ADR-0019）。`StructuredEmotionJudge.judge` 实现了完整的「调用 → 校验 → 重试 → 降级」闭环（`server-py/app/agent/emotion.py:53-83`）：

```python
async def judge(self, user_text: str) -> EmotionResult:
    if not user_text.strip():
        return EmotionResult.calm_default()
    validation_hint = ""
    for attempt in range(2):
        request_text = (
            user_text
            if not attempt
            else (
                user_text
                + "\n\n上次输出未通过结构校验："
                + validation_hint
                + "。请重新输出严格符合 Schema 的 JSON。"
            )
        )
```

注意三类失败的不同处理（`emotion.py:68-83`）：模型调用抛异常（含超时）**直接降级 calm 不重试**；`ValidationError` 把 pydantic 错误摘要塞进 `validation_hint`，拼进下一轮 prompt 让模型自我修正（最多重试 2 次）；返回内容不是合法 JSON 也走重试。两次都失败则回落 `calm_default()`。底层模型绑定 `response_format={"type": "json_object"}`，且专门关闭了思考、缩短超时、关掉网络重试（`emotion.py:86-98`）：

```python
class ChatOpenAIEmotionModel:
    """火山方舟 OpenAI 兼容接口的非流式情绪判断模型（json_object 模式）。"""

    def __init__(self, settings: Settings) -> None:
        # 情绪判断是轻量结构化任务：关闭思考、短超时、零网络重试（失败即降级 calm）。
        model = build_chat_model(settings, reasoning_effort="disabled", timeout=15, max_retries=0)
        self._model = model.bind(response_format={"type": "json_object"})

    async def ainvoke(self, user_text: str) -> str:
        response = await self._model.ainvoke(
            [SystemMessage(content=_SYSTEM_PROMPT), HumanMessage(content=user_text)]  # type: ignore[arg-type]
        )
        return response.content if isinstance(response.content, str) else ""
```

system prompt 只允许 calm / anxious / fearful 三档，明确要求「不结合任何健康档案，不做诊断或用药建议」（`emotion.py:22-30`）——情绪标注是 UI 反馈属性，不是医疗判断。

### 13.2 情绪挂载 message 事件

情绪不是独立的 SSE 事件，而是挂在 `message` 事件的负载里下发。`build_message_data` 在拼装最终消息时同步调用 judge（`server-py/app/services/chat_events.py:43-58`）：

```python
"""情绪属于最终 message 负载，失败由 judge 降级 calm；预问诊摘要不在此阻塞。"""
last_user_text = next(
    (message["content"] for message in reversed(messages) if message.get("role") == "user"),
    "",
)
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
```

判的是**用户最新一条消息**（`reversed(messages)` 找最后一个 user 角色），不是 AI 自己的回复。`soothing_text` 只在 anxious / fearful 时存在，文案来自 `contracts/emotion.json` 的 `soothing_texts`——双栈共享常量的单一事实源，改文案必须双栈同步发版。judge 的 `rationale` 仅用于调试，不下发、不落库。

### 13.3 工具调用：定义、注册与鉴权回调

情绪 judge 本身不挂工具，但它判断的对话轮次里大量事件来自工具调用——trace 链路正是围绕工具调用建立的，这里一次讲清工具从定义到回调的全链路。

**工具定义点**在 `server-py/app/tools/`：`business.py` 的 `build_business_tools` 用 `@tool` 装饰器定义业务工具（`recommend_doctors`、`get_doctor_slots`、`create_appointment`、`get_appointment` 等，`business.py:58-89`），函数体只做参数校验并转发 HTTP：

```python
@tool
async def recommend_doctors(department_name: str) -> dict[str, Any] | str:
    """按科室名称查询当前仍有号源的医生，用于导诊后的医生推荐。"""
    return await forward_get(
        client,
        "/api/agent/doctors/recommend",
        {"department_name": department_name},
        action="查询医生推荐",
    )

@tool
async def get_doctor_slots(doctor_id: int) -> dict[str, Any] | str:
    """按医生 ID 查询当前可预约的日期、时段和剩余号源。"""
    return await forward_get(client, f"/api/agent/doctors/{doctor_id}/slots", action="查询号源")
```

知识工具另有定义点：`tools/knowledge.py:32` 的 `build_knowledge_tool`（`search_knowledge`）与 `tools/graph.py:52` 的 `build_graph_tool`（`traverse_graph`），两者互斥注入。

**注册进 LangGraph** 在 `agent/runner.py`：`AgentRunner.__init__` 把三类工具分开持有，`_graph` 按 `(effort, knowledge_source, scenario)` 缓存编译图并调用 `create_agent`（`runner.py:146-162`）：

```python
def _graph(
    self, effort: ReasoningEffort, knowledge_source: str, scenario: str
) -> CompiledStateGraph[Any, Any, Any, Any]:
    key = (effort, knowledge_source, scenario)
    if key not in self._graphs:
        self._graphs[key] = create_agent(
            self._model_factory(effort),
            tools=self._tools_for(knowledge_source, scenario),
            system_prompt=(
                PRECONSULTATION_SYSTEM_PROMPT
                if scenario == _PRECONSULT_SCENARIO
                else SYSTEM_PROMPT
            ),
            context_schema=AgentContext,
            middleware=[_department_tool_choice_middleware],
        )
    return self._graphs[key]
```

`_tools_for`（`runner.py:134-144`）实现工具隔离：预问诊场景不暴露任何业务工具，rag 态注入 `search_knowledge`，graph 态注入 `traverse_graph`——隔离由编排代码保证而非提示词。

**鉴权回调**走 `tools/callback.py` 的 `BusinessCallbackClient`：所有业务工具都经这个带共享密钥头的 HTTP 通道回调 server-java（`callback.py:21-33`）：

```python
# server-java 是内网直连目标；绕过系统代理可避免本机代理把回调变成 502。
self._client = httpx.AsyncClient(
    base_url=base_url,
    timeout=timeout,
    transport=transport,
    trust_env=False,
    headers={"X-Agent-Callback-Token": callback_secret} if callback_secret else None,
)

async def _request_json(self, method: str, path: str, **kwargs: Any) -> Any:
    response = await self._client.request(method, path, **kwargs)
    response.raise_for_status()
    return response.json()
```

server-java 侧承接接口在 `controller/agent/`：`DoctorRecommendationController`（`/api/agent/doctors/recommend`）、`AppointmentToolController`（`/api/agent/appointments` 等）、`MedicationToolController`、`HospitalRecommendationController`、`StandardDepartmentToolController` 等。server-py 没有业务写入权，这是「业务写入只经 server-java」硬约束的物理实现。

### 13.4 trace 脱敏：只记摘要，不记原文

工具结果投影成 tool_end 事件时，`agent/events.py` 先做递归脱敏再截断。敏感字段是显式名单（`events.py:24-34`）：

```python
_MASK_SENSITIVE_KEYS = frozenset(
    {
        "query",
        "chunks",
        "entities",
        "summary",
        "condition_summary",
    }
)
_MASK_PLACEHOLDER = "[已脱敏]"
_TRACE_SUMMARY_MAX_LEN = 2000
```

`_mask_tool_output` 递归遍历工具返回体，命中名单的键整体替换为 `[已脱敏]`，医生、科室、号源等调试所需的业务结构保留；摘要超长 2000 字符再截断（`events.py:90-107`）：

```python
def _mask_tool_output(payload: Any) -> Any:
    """递归遮蔽健康原文；医生、科室、号源等调试所需业务结构继续保留。"""
    if isinstance(payload, dict):
        return {
            key: (_MASK_PLACEHOLDER if key in _MASK_SENSITIVE_KEYS else _mask_tool_output(value))
            for key, value in payload.items()
        }
    if isinstance(payload, list):
        return [_mask_tool_output(value) for value in payload]
    return payload
```

这就是 LLM 系统审计纪律的核心：**能回答「调了什么工具、成功没有、花了多久」，但回答不了「患者说了什么」**。同样地，`api/sse.py` 的流日志只记身份、事件名、字节数与计数（`sse.py:52-62`）：

```python
async for event in events:
    name = str(event["event"])
    counts[name] = counts.get(name, 0) + 1
    frame = sse_frame(name, event["data"])
    logger.debug(
        "sse frame conversation=%s event=%s bytes=%d",
        context.conversation_id,
        name,
        len(frame.encode("utf-8")),
    )
    yield frame
```

SSE 链路跨双栈多跳，这套四级日志（start / frame / complete / cancel / error）让每一跳都能回答「流走到哪、在哪断」，且取消与异常只留痕、原样上抛，不改变流语义。

### 13.5 trace 落库：独立可失败路径

server-java 侧，`ChatRoundService` 在中继 SSE 事件时把 trace 落库包进独立 try-catch（`ChatRoundService.java:319-325`）：

```java
private void persistTraceSafely(ChatRound round, String eventName, JsonNode data) {
    try {
        agentCallLogs.append(
                new AgentCallLogService.ChatRoundState(
                        round.getId(), round.getConversationId(), round.getPatientId()),
                eventName,
                data);
```

注释明确：异常只 `log.warn`，且**不记异常 message**（避免泄漏 SQL/连接串），只记 roundId / toolCallId / toolName / phase / 异常类名；trace 落库失败不是轮次失败，不下发错误、不写 `chat_rounds.error_code`。

`AgentCallLogService.append` 负责配对计时与白名单过滤（`AgentCallLogService.java:63-82`）：

```java
// tool_end：配对 tool_start 计算墙钟耗时
Long startNanos = state.takeStart(toolCallId);
Integer durationMs = startNanos == null ? null : (int) ((System.nanoTime() - startNanos) / 1_000_000);
String result = sse.isTraceResult(text(data, "result")) ? text(data, "result") : null;
// error_code 只存契约白名单码；非白名单统一记 TOOL_ERROR_UNKNOWN（ADR-0017）
String errorCode = "error".equals(result) ? contracts.sseEvents().traceErrorCodeUnknown() : null;
// 脱敏响应摘要（server-py 已遮蔽敏感原文，硬约束 5）；缺失时落 null
String summary = text(data, "tool_output_summary");
```

两个细节值得强调：`duration_ms` 由 server-java 用墙钟按 tool_start → tool_end 配对计算（`System.nanoTime()`，`AgentCallLogService.java:46-48` 记录起点），server-py 不背时钟；`result` 和 `error_code` 都过契约白名单，不在 `contracts/sse-events.json` 枚举内的值落 null 或统一记 `TOOL_ERROR_UNKNOWN`。配对状态存内存 `roundStates`，轮次结束由 `clearRound` 清理防泄漏（`AgentCallLogService.java:86-88`）。

### 13.6 B 端只读查询与调用链回放

`AgentCallLogController` 是项目首个角色鉴权接口：YAGNI 不引入注解/切面，controller 内就地检查 admin 角色（`AgentCallLogController.java:31-52`）：

```java
@GetMapping("/conversations")
public List<AgentCallLogService.ConversationView> conversations(
        @RequestAttribute(AuthFilter.ATTR_AUTH_ROLE) String role,
        @RequestParam(name = "patient", required = false) String patientKeyword) {
    requireAdmin(role);
    return service.listConversations(patientKeyword);
}

private void requireAdmin(String role) {
    if (!StaffUser.ROLE_ADMIN.equals(role)) {
        throw new ApiException(403, "仅管理员可查看 Agent 调用日志");
    }
}
```

不存在的 conversation_id 返回空列表而非 404——这是查询接口的幂等友好设计。admin 前端把扁平事件列表按 round_id 分组、组内按 seq 还原成时间线，tool_start 蓝色、tool_end 按 result 着色，tool_end 行可展开看脱敏响应（`admin/src/pages/AgentTrace/index.tsx:221-249`）：

```tsx
groupedLogs.map(([roundId, roundLogs]) => (
  <div key={roundId} style={{ marginBottom: 24 }}>
    <div style={{ fontWeight: 600, marginBottom: 12, color: '#595959' }}>
      轮次 #{roundId}
    </div>
    <Timeline
      items={roundLogs.map((log) => ({
        color:
          log.phase === 'tool_start'
            ? 'blue'
            : RESULT_COLORS[log.result ?? ''] ?? 'gray',
```

前端 `formatSummary` 尝试把脱敏摘要 JSON.parse 后美化展示（`index.tsx:44-51`），工具名中文文案与小程序 `TOOL_LABELS` 对齐——双端用语一致降低了排查时的认知成本。

## 契约与 ADR

- `contracts/emotion.json`：情绪三档枚举（calm/anxious/fearful）、默认值 calm、message 事件携带方式与 anxious/fearful 安抚文案的单一事实源，改此文件需双栈同步发版。
- `contracts/sse-events.json`：`trace_events: ["tool_start", "tool_end"]`、`trace_results`（success/error/skipped）与 `trace_error_code_unknown: TOOL_ERROR_UNKNOWN`；trace 事件集合与卡片事件严格不相交，由契约一致性测试钉死。
- `docs/adr/0017-agent-call-logs-redaction-and-availability.md`（agent_call_logs 的脱敏与可用性纪律）：`agent_call_logs` 字段集为白名单，**不设任何能装原文的列**——不存在的列无法被误写，运行时无需脱敏逻辑；落库走独立可失败路径。
- `docs/adr/0019-emotion-serial-second-llm-call.md`（情绪反馈由主回复完成后的串行二次 LLM 调用产生）：emotion 由非流式二次调用产出，json_object + pydantic 校验 + 2 次重试，失败降级 calm 不阻塞回复，rationale 不下发。
- 关联参考：`docs/adr/0010-cross-stack-contracts.md`（跨栈契约，注意 docs/adr 有两个 0010，本篇是跨栈契约那篇）与 `docs/adr/0020-asr-tts-not-in-agent-trace.md`（语音链路不进 Agent trace 的边界说明）。

## 讲解提示

- **审计纪律是第一主线**：让学生对比三处脱敏实现——`events.py` 的运行时递归遮蔽、`sse.py` 的「只记元数据」、ADR-0017 的「表结构白名单（不设原文列）」。强调纵深防御：server-py 脱敏是运行时保障，server-java 表结构白名单是物理保障，两层任一失守另一层仍兜底。提问「为什么表里没有 args/input 列」时，答案就是 ADR-0017 的那句话：不存在的列无法被误写。
- **学生常问：情绪判断为什么不在主回复里让模型顺带输出？** 答：主链路是纯 free-text token 流（为 TTFT 优化），结构化输出会拖慢首 token 且污染正文格式；串行二次调用把成本隔离在非关键路径上，且失败可独立降级 calm。代价是多一次 LLM 调用的延迟与费用——这正是 ADR-0019 权衡过的取舍。
- **学生常问：降级 calm 会不会掩盖故障？** 答：会，但这是刻意选择。emotion 是 UI 反馈属性（气泡配色），错误降级只是回落默认白泡，患者无感；相比之下阻塞或报错主回复才是事故。对比 `vision interpreter` 的 fail-fast 语义（图片解读失败是硬错误必须阻断）——降不降级取决于该能力是否在业务关键路径上。
- **trace 的边界**：tool_start/tool_end 只覆盖 Agent 工具调用，语音（ASR/TTS）按 ADR-0020 不进 trace；duration_ms 由 server-java 墙钟配对计算而非 server-py 上报，可引导学生讨论「为什么不让产生事件的一端自己计时」（时钟一致性 + 不可信输入原则）。

> 返回目录：[docs/textbook/README.md](./README.md)

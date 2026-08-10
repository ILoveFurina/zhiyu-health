# 模块2：智能导诊（挂号意图识别与科室推荐）

## 业务概述

C 端患者在对话中表达“挂号”或“挂什么科”的需求时，系统需要将症状收敛到平台维护的“标准科室”，并给出当前服务城市未来 14 天的跨医院号源卡。本模块的核心设计是**意图识别零 LLM 调用**：server-py 编排层用关键词标记确定性识别挂号/导诊意图，LLM 只在必要时被锁定为“科室选择工具的首轮调用方”，医院、医生、排班、余号等事实全部由 server-java 确定性返回，模型不得自由生成（ADR-0027）。卡片点选与失败重试更是完全绕过 Agent，直接按可信科室 ID 直查号源。

## 业务流程

1. 小程序 chat 页发送文本，`hospital-routing.js` 的 `sendText` 先 `ensureLogin()`，再把本次会话已确认的就医位置坐标随对话请求上送（`startRound(content, { longitude, latitude })`）。
2. server-java 对话入口先执行红线症状规则引擎（确定性判断，命中则中断普通挂号/导诊流程），然后把对话请求经 SSE 转发给 server-py。
3. server-py `ChatService.stream` 先处理两类“绕过 Agent”的快路径：请求携带 `retry_standard_department_id`（科室选择卡点选或号源卡失败重试）时，直接由 `GuidedRegistrationFlow.stream_slots` 直查号源，输出 `message 摘要 → department_slots 卡 → done`，不再进入 Agent。
4. 否则进入 `DepartmentToolPolicy.resolve`：**不调用任何模型**，用关键词标记判断本轮是否需要科室工具，命中时拉取 server-java 的标准科室目录，产出 `DepartmentToolPlan`（受控 `tool_choice` + 可信目录）。
5. `LangGraphAgentRunner` 通过模型调用中间件把首轮工具锁定为 `get_standard_department_slots`（明确科室）或 `suggest_standard_departments`（需要推荐 2–3 个候选科室），可信目录经系统消息注入，模型只能从目录中逐字选择。
6. 工具函数内再次用 server-java 目录校验科室名（模型只决定参数，事实由 server-java 提供），然后经业务回调查询号源；结果按契约投影为 `department_slots` / `department_options` 卡事件。
7. 小程序端 `department-slots-card` 组件渲染跨医院医生与号源（有号优先、无号置灰禁用），用户点击预约跳挂号业务页；点选科室选择卡则携带 `retry_standard_department_id` 回到步骤 3 的直查快路径。

## 代码地图

| 层 | 职责 | 文件路径 |
|---|---|---|
| server-py services | 意图识别策略（零 LLM）：关键词标记 + 目录拉取，产出受控工具计划 | `server-py/app/services/department_tool_policy.py` |
| server-py services | 对话主流程编排：重试直查快路径、policy 调用、AgentContext 装配 | `server-py/app/services/chat.py` |
| server-py services | 确定性号源卡流程（绕过 Agent 的直查/重试路径） | `server-py/app/services/chat_guidance.py` |
| server-py tools | 科室目录回调适配器与两个 `@tool` 工具定义点 | `server-py/app/tools/department.py` |
| server-py agent | LangGraph 装配：中间件锁定首轮工具、可信目录注入、卡片投影 | `server-py/app/agent/runner.py` |
| server-py tools | 业务回调客户端（携带回调令牌 HTTP 调 server-java） | `server-py/app/tools/callback.py` |
| server-java controller | 标准科室目录与跨医院号源的 Agent 回调接口 | `server-java/src/main/java/com/zhiyu/health/controller/agent/StandardDepartmentToolController.java` |
| server-java service | 服务城市解析与标准科室号源聚合查询 | `server-java/src/main/java/com/zhiyu/health/service/health/PatientMedicalDirectoryService.java` |
| server-java config | `/api/agent/**` 回调令牌鉴权过滤器 | `server-java/src/main/java/com/zhiyu/health/config/AgentCallbackAuthFilter.java` |
| miniprogram | 对话发送与意图自动分档（导诊 low / 解读 high） | `miniprogram/pages/chat/hospital-routing.js` |
| miniprogram | AI 挂号助手主卡片（自助挂号 / 智能导诊入口） | `miniprogram/components/registration-card/` |
| miniprogram | 科室号源卡渲染与预约交互 | `miniprogram/components/department-slots-card/` |

## 核心代码走读

### 2.1 零 LLM 意图识别：DepartmentToolPolicy

`server-py/app/services/department_tool_policy.py:7-18` 定义了全部意图标记，纯关键词匹配，不消耗任何模型调用：

```python
_BOOKING_MARKERS = ("挂号", "预约", "号源", "挂")
_DEPARTMENT_QUESTION_MARKERS = (
    "挂什么科",
    "看什么科",
    "该挂哪",
    "该看哪",
    "哪个科",
    "哪一科",
    "什么科室",
)
_DEPARTMENT_SLOTS_TOOL = "get_standard_department_slots"
_DEPARTMENT_OPTIONS_TOOL = "suggest_standard_departments"
```

判定逻辑在 `resolve`（`server-py/app/services/department_tool_policy.py:35-70`）：只在 `scenario == "triage"` 且目录可用时工作；最新一条用户消息命中挂号标记且**逐字包含目录中的科室名**时锁定号源直查工具；命中“挂什么科”类疑问（或前文问过、本轮在续答）时锁定科室候选工具；都不命中则返回空计划，本轮按普通对话处理：

```python
        booking_intent = any(marker in latest for marker in _BOOKING_MARKERS)
        department_question = any(marker in latest for marker in _DEPARTMENT_QUESTION_MARKERS)
        continuing_triage = len(user_messages) > 1 and any(
            marker in text for text in user_messages[:-1] for marker in _DEPARTMENT_QUESTION_MARKERS
        )
        if not booking_intent and not department_question and not continuing_triage:
            return DepartmentToolPlan()

        candidates = await self._directory.list_departments(longitude, latitude)
        if isinstance(candidates, str) or not candidates:
            return DepartmentToolPlan()
        catalog = tuple((item["id"], item["name"]) for item in candidates)

        normalized_latest = _normalized(latest)
        exact_department = next(
            (name for _, name in catalog if _normalized(name) in normalized_latest), None
        )
        if booking_intent and exact_department is not None:
            return DepartmentToolPlan(_DEPARTMENT_SLOTS_TOOL, catalog)
        if department_question or continuing_triage:
            return DepartmentToolPlan(_DEPARTMENT_OPTIONS_TOOL, catalog)
```

这是本模块的降本要点：挂号意图识别这种高频、模式固定的判断，用一次字符串包含匹配替代了一次 LLM judge 调用——零延迟、零 token 成本、零误判不可解释性。关键词覆盖不到的表达会退化为普通对话，由主 Agent 自行处理，不影响可用性。

### 2.2 编排快路径：重试与点选不进 Agent

`ChatService.stream` 在调用 runner 之前先处理两类确定性快路径（`server-py/app/services/chat.py:87-98`）：

```python
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

点选科室选择卡或号源卡失败重试时，端侧在对话请求里携带契约字段 `retry_standard_department_id`（可信的科室 ID，不是模型猜的名字），server-py 跳过整个 Agent 直接查号源。`GuidedRegistrationFlow.stream_slots`（`server-py/app/services/chat_guidance.py:31-65`）固定产出 `message 摘要 → department_slots 卡 → done` 三个事件，连失败卡的消息文案都取自契约模板：

```python
        yield {
            "event": _GUIDED.card_event,
            "data": {**result, "status": _GUIDED.card_statuses[0], "disclaimer": self._disclaimer},
        }
        yield {"event": EVENT_DONE, "data": {}}
```

### 2.3 工具调用：`@tool` 定义与 runner 的首轮锁定

**工具定义点**在 `server-py/app/tools/department.py:145-192` 的 `build_department_tools`，两个 `@tool` 函数是模型可调用的全部科室能力：

```python
    @tool
    async def get_standard_department_slots(
        department_name: str, runtime: ToolRuntime[AgentContext]
    ) -> dict[str, Any] | str:
        """必须用于明确科室的挂号/预约/号源请求，按标准科室名称查询未来 14 天跨医院号源。"""
        candidates = _context_candidates(runtime.context) or await directory.list_departments(
            runtime.context.longitude, runtime.context.latitude
        )
        if isinstance(candidates, str):
            return candidates
        selected, _ = _resolve_names([department_name], candidates)
        if len(selected) != 1:
            return f"未找到标准科室“{department_name.strip()}”。{_catalog_hint(candidates)}"
        slots = await directory.get_slots(
            selected[0]["id"], runtime.context.longitude, runtime.context.latitude
        )
```

注意模型传入的是**科室名称字符串**，工具内部用可信目录做归一化匹配（`_resolve_names`），命中唯一科室才取 ID 查询；匹配不到就返回可用目录提示，让模型自行纠正。这就是“模型决定参数、事实由 server-java 提供”的边界。

**注册与锁定**在 `server-py/app/agent/runner.py`。工具在启动装配时经 `bootstrap.py` 注入 runner（`build_department_tools(directory)` 与业务工具一起作为 `tools` 传入），`create_agent` 编译图时挂载中间件（`runner.py:151-161`）：

```python
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
```

中间件 `_with_department_tool_choice`（`runner.py:62-87`）在每次模型调用前改写请求：首轮只暴露 policy 选定的那一个工具并设 `tool_choice="required"`，等价于强制模型首轮必须调用该工具；一旦消息流中出现了 ToolMessage（工具已返回），立即把后续轮次的 `tool_choice` 改为 `"none"`，防止模型拿到结果后继续串联或重复查卡：

```python
    choice = request.runtime.context.department_tool_choice
    if choice is None:
        return request
    if any(isinstance(message, ToolMessage) for message in request.messages):
        # 一轮只允许产出一种科室卡，避免模型在拿到结果后继续串联或重复查卡。
        return request.override(tool_choice="none")
    allowed_names = {choice} if choice in _DEPARTMENT_TOOL_NAMES else _DEPARTMENT_TOOL_NAMES
    department_tools: list[BaseTool | dict[str, Any]] = [
        tool for tool in request.tools if isinstance(tool, BaseTool) and tool.name in allowed_names
    ]
```

对 `suggest_standard_departments` 还有一个工程细节（`runner.py:79-86`）：候选参数在方舟流式响应中会被拆分，锁定版 agent 无法可靠聚合，因此该分支关闭流式（`disable_streaming`）并换用专用系统提示词，改走 `stream_mode="updates"` 的完整节点投影（`runner.py:212-230`），最多重试 3 次后给兜底文案。可信目录则通过系统消息注入（`runner.py:287-296`），要求模型“只能从该目录逐字选择，不得改写或编造名称”。

**回调与鉴权**：工具的号源/目录查询经 `BusinessCallbackClient`（`server-py/app/tools/callback.py:22-28`）发出，客户端在请求头携带 `X-Agent-Callback-Token`：

```python
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout,
            transport=transport,
            trust_env=False,
            headers={"X-Agent-Callback-Token": callback_secret} if callback_secret else None,
        )
```

server-java 侧由 `AgentCallbackAuthFilter`（`config/AgentCallbackAuthFilter.java:13-18`）对 `/api/agent/**` 统一校验该令牌，承接接口是 `StandardDepartmentToolController` 的两个只读端点（`StandardDepartmentToolController.java:30-50`）：

```java
    @GetMapping
    public StandardDepartmentCatalog catalog(
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double latitude) {
        String cityCode = directory.resolveServiceCityCode(Coordinates.fromNullable(latitude, longitude));
        return new StandardDepartmentCatalog(directory.standardDepartments(cityCode).stream()
                .flatMap(category -> category.departments().stream()
                        .map(department ->
                                new StandardDepartmentEntry(department.id(), department.name(), category.category())))
                .toList());
    }

    @GetMapping("/{standardDepartmentId}/slots")
    public PatientMedicalDirectoryService.StandardDepartmentSlotsView slots(
            @PathVariable @Positive long standardDepartmentId, ...
```

与 C 端目录接口的差异在于：Agent 回调不传 `city_code`，由服务端按可选坐标解析当前服务城市（`PatientMedicalDirectoryService.resolveServiceCityCode`，无坐标取服务城市首项），跨医院查询只使用标准科室 ID。

### 2.4 摘要不许模型自由生成：build_slots_summary

号源卡的引导文案也是确定性的。`build_slots_summary`（`server-py/app/tools/department.py:114-142`）只用 server-java 的返回值，按契约模板拼装摘要——找最早可约时段、统计有号医生数、追加推荐理由：

```python
    earliest_doctor = min(
        earliest_pool,
        key=lambda item: (_earliest_key(item["earliest_bookable"]), item.get("doctor_id") or 0),
    )
    earliest = earliest_doctor["earliest_bookable"]
    slot = earliest.get("time_slot", "")
    summary = _GUIDED.summary_templates["ok"].format(
        department=name,
        earliest_date=earliest.get("date", ""),
        earliest_slot=_GUIDED.time_slot_labels.get(slot, slot),
        doctor_count=len(bookable),
    )
```

模板本身（“已为您查询{department}号源：最早可约……”）定义在 `contracts/guided-registration.json` 的 `summary_templates`，双栈共享。LLM 从头到尾不参与医院、医生、地址、距离、排班、余号任何事实的表述。

### 2.5 小程序端：意图分档与号源卡渲染

发送侧 `miniprogram/pages/chat/hospital-routing.js:4-11` 同样是关键词级确定性判断，用于选择推理档位——命中“解读/报告/处方”走 `interpretation`（high），否则走 `triage`（low）：

```javascript
const INTERPRETATION_KEYWORDS = ['解读', '报告', '处方']

/** 自动档按意图分配：导诊 low，报告/处方解读 high。 */
function scenarioFor(content) {
  return INTERPRETATION_KEYWORDS.some((keyword) => content.includes(keyword))
    ? 'interpretation'
    : 'triage'
}
```

渲染侧 `miniprogram/components/department-slots-card/index.js:85-92` 处理预约点击：server-java 已按“有号优先”排序，无号医生保留展示但置灰，端侧再按 `remaining_slots` 防御一次，防御点按瞬间数据已滞后：

```javascript
    book(e) {
      const { doctorIndex, slotIndex } = e.currentTarget.dataset
      const doctor = this.data.doctorsView[doctorIndex]
      const slot = doctor && doctor.slots[slotIndex]
      // 已约满仅置灰展示，仍在 js 侧按剩余号源挡一次，防御点按时数据已滞后
      if (!slot || Number(slot.remaining_slots) <= 0) return
      this.props.onBook({ scheduleId: slot.schedule_id, doctor, slot, cardId: this.props.cardId })
    },
```

`registration-card`（AI 挂号助手主卡）则只负责入口分流：`onDepartmentEntry` 进自助科室挂号、`onGuideEntry` 进智能导诊对话（ADR-0027 的“两条路径共享同一套号源数据”）。

## 契约与 ADR

- `contracts/guided-registration.json`：本模块的单一事实源——卡片事件名（`department_slots` / `department_options`）、卡状态（`ok`/`failed`）、重试直查字段 `retry_standard_department_id`、摘要文案模板、时段标签（AM→上午/PM→下午）、候选科室上限 3。
- `contracts/sse-events.json`：SSE 事件序列（`message` / `done` 等）的事实的源，`chat_guidance.py` 与 `chat.py` 均从契约加载事件名。
- ADR-0027（AI 挂号助手：AI 只确定标准科室，号源与挂号保持确定性）：Agent 只负责收敛标准科室，城市、距离、医生、余号、挂号全部由 server-java 确定性处理；科室号源卡统一为有号优先、无号禁用预约。
- ADR-0010（跨栈契约：contracts/ JSON 单一事实源 + 双栈启动加载）：本模块所有事件名、字段名、模板都经 contracts 共享，改契约需双栈同步发版（注意与同编号的 ADR-0010《RAG 知识检索只用于受控证据问答与技术演示》区分，docs/adr 中存在两个 0010）。

## 讲解提示

- **降本设计的核心论证**：意图识别是高频且模式封闭的判断（几个固定句式），用关键词匹配替代 LLM judge，省掉的是每一轮对话都可能发生的一次额外模型调用。教学上可引导学生对比“LLM judge 判意图”方案的成本、延迟与不可解释性，并强调降级策略：识别不到就退化为普通对话，而不是误判。
- **常见提问：为什么工具内还要再校验科室名？** 因为模型可能编造或改写名称。可信目录经 `AgentContext` 注入工具运行时，`_resolve_names` 归一化匹配是唯一信任源；匹配失败返回目录提示让模型自我纠正，形成“模型提议、代码裁决”的闭环。
- **常见提问：`tool_choice="required"` 为什么不直接指定函数名？** 代码注释（`runner.py:78`）说明火山方舟对 `required` 的支持比指定函数名稳定，因此用“首轮只暴露一个工具 + required”实现等价约束；拿到工具结果后立即 `tool_choice="none"` 锁死后续工具调用。
- **常见提问：点选卡片为什么还要发一轮对话请求？** 端侧点选复用对话通道，但携带 `retry_standard_department_id`（可信 ID）直查号源，跳过科室解析与 Agent 回复——既保持了统一的消息流 UX，又保证重试路径零模型成本。

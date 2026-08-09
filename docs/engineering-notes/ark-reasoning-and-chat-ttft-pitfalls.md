# 方舟深度思考、Quick TTFT 与 Agent 工具流排障记录

本文记录 2026-08-09 修复票 81 时遇到的三组耦合问题：深度思考档没有任何可见思考增量、快速回答首字仍要等待约 5 秒，以及移除前置导诊 judge 后科室工具调用不稳定。三个症状都发生在“模型开始生成之前或生成流的转换层”，只看最终回复或 fake 测试很容易得出错误结论。

相关实现：

- `server-py/app/core/llm.py`：方舟 `reasoning_content` 流式适配
- `server-py/app/services/department_tool_policy.py`：零 LLM 调用的科室工具首轮路由
- `server-py/app/agent/runner.py`：LangGraph 工具约束及候选科室完整节点流
- `server-py/app/tools/department.py`：标准科室目录校验、候选卡和号源卡工具
- `server-py/app/services/chat.py`：普通对话关键路径
- `miniprogram/utils/ai-bubble-state.js`：quick/deep 等待态与 thinking 展示
- `server-py/tests/test_ark_chat_model.py`、`test_chat_department_tools.py`、`test_department_tool_policy.py`：本次新增的紧回归

## 1. 原始方舟流有思考内容，但 LangChain 转换层把它丢了

### 症状

深度思考档能够正常返回正文，C 端却始终收不到 `thinking` 事件。原有测试直接构造：

```python
AIMessageChunk(content="", additional_kwargs={"reasoning_content": "..."})
```

因此测试一直是绿的，但它绕过了真实 OpenAI 兼容响应到 `AIMessageChunk` 的转换过程。

### 最小反馈环

诊断必须同时观察两层：

1. 用原始 OpenAI 客户端读取方舟 SSE，确认 `choices[0].delta.reasoning_content` 是否存在；
2. 用项目的 `ChatOpenAI` 适配器读取同一模型流，检查 `AIMessageChunk.additional_kwargs`。

实测第一层持续收到 `reasoning_content`，第二层为空。由此排除模型档位、C 端折叠状态和 server-java 透传，问题被缩到 `langchain-openai 0.3.34` 的 delta 转换函数。

### 根因与修复

锁定版本的 `_convert_delta_to_message_chunk` 只转换标准 OpenAI 字段。方舟扩展的 `reasoning_content` 不在白名单中，进入 LangChain 前就被静默丢弃。最终修复是在唯一模型构建点使用 `ArkChatOpenAI`，覆盖 `_convert_chunk_to_generation_chunk`：

- 先调用父类，保留 LangChain 对正文、usage 和工具调用的正常处理；
- 再从原始 delta 读取非空 `reasoning_content`；
- 只把该字段写入 `AIMessageChunk.additional_kwargs`，不透传其他未知或加密字段。

这类兼容层测试必须喂“供应商原始 chunk 形状”，不能从已经加工好的 LangChain 消息开始。真实模型回归中 high 档收到 98 个 thinking chunks，说明修复覆盖了真实链路。

## 2. “正在回复”不是 TTFT，串行 judge 才是主要等待来源

### 先分清三个时间点

- 请求被接受：server-java 建立对话轮次；
- `meta` 到达：C 端知道本轮实际推理档位，显示“正在回复…”；
- 首个正文 token：用户真正看到回答开始出现，这才是本次关注的 TTFT。

等待文案出现得快，只能说明连接和首个元事件正常，不能证明模型首字快。

### 分段计时结果

修复前的 quick 普通导诊请求包含两个串行模型调用：

```text
StructuredTriageJudge：约 2.46–3.09 秒
主 Agent 首 token：约 1.99–2.86 秒
端到端首正文：约 4.60–5.64 秒
```

前置 judge 没有向用户输出 token，却完整占用了第一次模型 TTFT。把主模型调快或提前显示等待气泡都无法消除这段时间。

### 最终边界

- 删除普通对话关键路径上的独立 triage judge；
- 普通“你好”等请求不查标准科室目录，只调用主模型一次；
- 只有出现明确挂号或“该挂什么科”意图时，代码才读取 server-java 的可信标准科室目录，并约束首轮使用对应科室工具；
- 明确科室时模型只生成标准科室名称参数，号源事实全部由 server-java 返回；
- 多科室场景模型只从可信目录生成 2～3 个候选名称，工具确定性补齐 id；
- 卡片产出后使用确定性短引导，不再发起第二次模型整理，避免重复工具循环。

修复后 quick 真实模型 5 次 TTFT 为 `1144 / 3122 / 680 / 2073 / 1111 ms`，中位数 1144 ms、最大值 3122 ms。剩余长尾是单次供应商模型请求，而不是本地串行编排。

## 3. `tool_choice=auto`、`required` 和流式工具参数都不能想当然

### `auto` 不保证遵守“必须调用”提示词

即使系统提示明确要求“用户说要挂皮肤科时必须调用工具”，真实模型仍可能直接输出文字。提示词是行为引导，不是协议约束；卡片是否出现不能只靠 prompt。

### 指定函数名不如“只暴露一个工具 + required”稳定

方舟 OpenAI 兼容接口在本次实测中对指定函数名的 `tool_choice` 表现不稳定。更可靠的做法是：

1. 代码先根据明确意图区分号源卡或候选卡；
2. 首轮只向模型暴露对应的一个工具；
3. 使用 `tool_choice="required"`；
4. 工具返回后立即结束本轮工具循环。

这仍让模型负责医学语义到标准科室名称的选择，但不会让它在购药、医生推荐、知识图谱等无关工具之间误选。

### 候选科室参数暴露了锁定版流式聚合缺陷

候选工具最初接收 `list[str]`。原始方舟流把 JSON 参数拆成多个 delta；手工累加 `AIMessageChunk` 可以得到完整参数，但锁定版 `create_agent` 在该场景偶发只保留空 chunk，工具节点不执行。改为逗号/顿号分隔的字符串后仍存在供应商空 required 响应的长尾。

最终处理：

- 候选工具首轮使用紧凑专用 system prompt，避免携带整份通用 Agent 提示词；
- 首轮关闭模型流式输出，因为工具选择阶段本来没有用户正文；
- 从 LangGraph `updates` 读取完整 AIMessage 和 ToolMessage，而不是从碎片化 `messages` 流拼工具参数；
- 在完整节点尚未形成候选卡时最多安全重试 3 次，所有中间 trace 先缓冲，避免用户看到半轮或重复进度；
- 一旦得到 `department_options` 即停止图执行并下发确定性引导，禁止模型继续串联多个号源卡。

真实模型连续 3 次回归均得到：

```text
tool_start → tool_end → department_options → token
```

另一个伴生问题是 OpenAI 兼容流会产生 `name` 为空的工具参数续传 chunk。它不是新的工具调用；`agent/events.py` 必须忽略空工具名，否则 C 端会多显示一个无名称的工具进度。

## 4. Quick 等待文案不要重复解释同一件事

原 quick 状态在 0 秒显示“正在回复…”，3 秒后改成“正在为您仔细整理回复…”。第二句没有新增状态，只让一次正常的供应商长尾显得像系统又进入了新阶段。

当前约定：

- quick / auto-disabled：只显示“正在回复…”，不设置延迟文案定时器；
- deep：保留深度思考初始文案、9 秒复杂问题提示和 thinking 折叠区；
- 工具开始/结束：继续用真实工具名显示进度，不受 quick 文案精简影响。

端侧回归要验证的不只是字符串删除，还要验证 quick 收到 `meta` 后不会新建延迟 timer。票 81 使用可控 `setTimeout/clearTimeout` 的 Node harness 锁定了这一点。

## 5. 推荐的排障顺序

遇到“深度思考不显示”或“快速回答仍很慢”时，按以下顺序检查：

1. 原始供应商 SSE 是否有 `reasoning_content`、正文和工具 delta；
2. LangChain 消息转换后相应字段是否还在；
3. server-py 首个 `meta/thinking/token/tool_start` 各自何时产生；
4. server-java 是否逐事件透传，还是缓冲到轮次完成；
5. C 端是否收到事件但被等待态、折叠态或 replay 层隐藏；
6. 对 TTFT 分段计时，列出每个串行模型调用，不能只测总耗时；
7. 工具问题同时检查原始 tool delta、聚合后的 AIMessage、ToolNode 执行和 SSE 投影，不能只看最终回复。

相关回归命令：

```bash
uv run pytest
uv run ruff check server-py
uv run ruff format --check server-py
uv run mypy server-py/app
uv run lint-imports
mvn -f server-java/pom.xml -Dtest=ContractsTest,ContractsConsistencyTest,ChatRoundServiceTest test
mvn -f server-java/pom.xml spotless:check
```

## 6. 不应回退的边界

- 不要用直接构造 `additional_kwargs` 的 fake 代替原始供应商 chunk 回归；
- 不要为了导诊在所有普通 quick 请求前增加第二个 LLM judge；
- 不要把医院、医生、排班、余号或标准科室 id 交给模型生成；
- 不要全局设置 `tool_choice="required"`，否则普通健康问答也会被迫调用业务工具；
- 不要在候选卡后继续让模型自由调用科室工具，否则可能产生重复卡或多个科室号源卡；
- 不要把 thinking 内容持久化到消息或 trace；它只在实时页面内存中展示；
- 所有 AI 正文和卡片仍必须携带“仅供参考，不替代医生诊断”。

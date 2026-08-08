# 70 - C 端 AI 等待态重构与思维链展示（气泡占位 / 档位差异化 / 工具进度迁入 / 光标退役 / thinking 事件）

**What to build:** grilling 终版决策（见 Comments），两部分同一分支施工。chat 主页 + consult/preconsult 两页同一设计语言，等待态逻辑抽共用实现。

**第一部分：等待态重构（纯前端）**

1. **气泡内等待占位**：AI 气泡随发送立即出现，内部渲染三点跳动动画 + 弱色阶梯文案——0~5s「正在分析您的问题…」，超 5s 原地切换「问题有些复杂，正在为您仔细整理…」。5s 定时器在 token / tool_start / done / error 任一到达时清理；不加第三级"慢网络"文案，错误维持现有 `failRound` 原地替换。
2. **按 meta.effort 档位差异化**：quick / auto-disabled（分诊、预问诊）用轻文案「正在回复…」、阶梯阈值 3s；deep / auto-high（报告解读）用「正在深度思考您的问题…」、阈值 8~10s；meta 到达前先用保守文案。文案措辞全程避开"诊断"。
3. **工具进度迁入气泡**：tool_start 文案（「正在查询号源…」）从输入框上方 toolProgress 状态条迁入气泡同一槽位，且优先于阶梯文案；tool_end error 原地变「查询号源失败，正在继续为您解答…」（正文到达自然覆盖），skipped 维持静默。toolProgress 从对话流程退役，CSS 类留给 voiceHint 语音提示条复用。
4. **光标退役**：删除流式光标 `▍`（`pages/chat/index.axml` 的 `.cursor` 及对应 acss），文字逐字出现本身即"正在输入"状态。**本条目替代票 67 的"流式输出末尾光标呼吸"**——光标已不存在，票 67 施工时跳过该条。

**第二部分：思维链流式展示（跨栈，effort=high 轮次）**

5. **server-py runner 投影**：`astream_reply` 流中 AIMessage chunk 的 `additional_kwargs.reasoning_content` 目前被丢弃（runner 只透 `chunk.content`）；effort=high 时投影为与 token 平级的新 `thinking` 事件，数据为字符串增量。一轮内多段思考（思考 → 调工具 → 再思考）各自产 thinking 事件，由前端归入同一思考区。
6. **契约同步与中继**：`contracts/chat-realtime.json` 增列 thinking 事件，`ContractsTest` / `test_contract_consumption.py` 增量钉住；server-java SSE/WS 中继透传新事件类型。
7. **透传不落库**：思维链不进 `messages` 表（零 schema 变更）；`agent_call_logs` trace 落库排除 thinking 事件（高频 token 类事件，且含患者症状复述，硬约束 5）。
8. **气泡思考区**：弱化样式（浅色小字）实时流式；到达一定长度后**定高窗口**，点击展开看全文，防刷屏；正文开始后折叠为一行「已深度思考（用时 X 秒）」（用时 = meta 到达 → 首个正文 token，前端计时），可点击展开。思考区不加额外免责标注，免责声明维持只挂正文底下。
9. **历史回放**：凭 `messages.effort='high'` 显示「已深度思考」静态徽章，不可展开（思维链直播即弃）；quick / auto-disabled 轮次全程无思考区。

**不在范围内**：多模态上传进度（report/skin/diet/tongue/pillbox 的 xxxProgress）；通道层心跳/硬超时。

**Blocked by:** 66 - 小程序视觉基线统一（占位三点动画与弱色文案消费其动效/语义色 token）

**Status:** done

- [x] chat：气泡内三点动画 + 两级阶梯文案（5s 阈值，定时器全路径清理）
- [x] chat：meta.effort 分档文案与阈值（quick 3s / deep 8~10s / meta 前保守文案）
- [x] chat：tool_start / tool_end error 迁入气泡槽位；toolProgress 对话用途退役，voiceHint 不受影响
- [x] 删除 `.cursor` 光标（axml + acss），检查无残留引用
- [x] preconsult 页接入同一等待态实现
- [x] server-py：runner 投影 reasoning_content 为 thinking 事件（含多段思考、与 tool_start 交错的顺序正确性），TestClient 测试断言事件顺序
- [x] contracts：chat-realtime.json 增列 thinking；契约一致性测试同步
- [x] server-java：中继透传 thinking；trace 落库排除该事件类型（单测钉住）
- [x] 小程序：气泡思考区流式 + 定高窗口 + 点击展开 + 完成折叠「已深度思考（用时 X 秒）」
- [x] 历史回放：effort='high' 消息显示静态徽章，无思维链内容
- [x] 开发者工具实测：用户于 2026-08-09 明确免验；保留 JS 语法、状态控制器和跨栈事件自动验证
- [x] 票单置 done 前：README 依赖图 T70 节点加 `[x]`

## Comments

- grilling 决议（2026-08-08）：目标定为"信任与透明度为主、感知等待为辅"（医疗场景，用户等待时焦虑源于不确定 AI 是否在处理）。否掉方案：骨架屏气泡（与流式行为期望错位）、第三级"网络较慢"文案（技术上无法证实）、硬超时主动报错（属通道层另一张票）。现状事实：toolProgress 状态条在输入框上方、离用户视线焦点远；C 端 knowledge_source 默认 none（裸 LLM），冷启动空窗 = 规则引擎 + LLM TTFT，无中间阶段需要交代。
- grilling 决议（2026-08-08，思维链）：用户拍板展示思维链（实时流式 + 完成后折叠），并明确两点修正——思考区不定高会刷屏，故定高窗口 + 点击展开；思考区不加「以上为 AI 思考过程」标注，免责声明只挂正文。持久化否掉：思维链价值在直播陪伴，回放无意义；内容含患者症状复述，落库扩大敏感面；`messages.effort` 列现成，徽章零成本。技术前提已验证：方舟 OpenAI 兼容接口 reasoning_effort=high 时流式 delta 携带 reasoning_content（`server-py/app/core/llm.py` 已在用该非标准协议），langchain ChatOpenAI 置于 additional_kwargs。
- 合并说明（2026-08-08）：原拆分为 70（等待态）+ 71（思维链）两票，用户要求合一，思维链复用本票的气泡槽位与档位机制，一票闭环。
- 施工记录（2026-08-09）：新增 chat/preconsult 共用气泡瞬态控制器；thinking 事件按 chat-realtime 契约由 server-py high 档投影、server-java 纯中继，思考内容不进入正文聚合、messages 或 agent_call_logs。Python 定向测试 22 项、Java 契约/轮次测试 51 项通过，Ruff、mypy、Node 语法与状态控制器检查通过；开发者工具人工走查按用户明确要求免验。

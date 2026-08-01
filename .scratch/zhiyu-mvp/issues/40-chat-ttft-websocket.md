# 40 — 对话首响应提速与 WebSocket 实时链路

Status: done

**What to build:** 降低 C 端对话的用户可见首响应时间：快速回答真正关闭模型思考；小程序与 server-java 之间改为页面级 WebSocket 长连接，server-java 继续以 SSE 调 server-py，保持统一鉴权、审计、红线规则和业务写入入口。

## Checklist

- [x] 快速回答始终使用 `thinking.type=disabled`；自动档普通对话/导诊关闭思考、复杂解读使用 high；深度思考始终使用 high
- [x] 从 C 端 Agent 移除 `check_contraindication` 工具和对应 SSE 卡片映射；Agent 只做通用药品知识解释，B 端医生开方继续强制执行 server-java 禁忌检查
- [x] 聊天页打开期间维持一条 WebSocket，页面卸载时关闭；同一时刻仅允许一轮对话
- [x] WebSocket 握手仅通过 `Authorization: Bearer …` 携带患者 JWT，禁止把令牌放入 URL
- [x] server-java 将 server-py SSE 事件实时转发为 WebSocket 消息，不改变既有 SSE 事件语义
- [x] 保留 `POST /api/c/chat` 作为诊断/非小程序 SSE 适配器；它与 WebSocket 共用同一个 `ChatRoundService`，两者都要求 `request_id`
- [x] WebSocket 使用 `contracts/` 定义的结构化 JSON 信封：`chat`、`accepted`、`event`、`error`，每条消息携带 `request_id`
- [x] PostgreSQL 持久化对话轮次并以 `(patient_id, request_id)` 保证入口幂等；状态为 `ACCEPTED / RUNNING / COMPLETED / FAILED`
- [x] server-java 仅在进程内持有运行任务和实时观察者，不为对话轮次增加 Redis 缓存
- [x] WebSocket 断开只移除实时订阅者，不取消已接受的对话轮次；该轮继续执行并持久化
- [x] 重进聊天页后通过对话记录恢复已经完成的回答，不自动重放可能产生业务副作用的请求
- [x] WebSocket 建连失败时以同一 `request_id` 回退 SSE；不做断线自动重新附着，任何自动流程都不得生成新 ID 重跑
- [x] token 生成期间显示正文和生成中状态；仅在最终 `message` 到达后显示免责声明，历史回放同样显示，红线规则不显示
- [x] 增加首事件、首 token 和完成耗时的脱敏可观测指标
- [x] 真实模型快速/自动普通对话连续 5 次：首 token 中位数 ≤ 3 秒、单次最大值 ≤ 5 秒；深度思考只记录不设硬阈值
- [x] fake Agent 自动化测试断言 server-py 发出首 token 后，server-java → WebSocket 额外转发延迟 ≤ 100ms；离线 CI 不调用真实模型
- [x] server-java、server-py 回归测试覆盖快速档映射、实时首 token、断连后继续生成与持久化
- [x] 支付宝开发者工具实测登录 → 对话逐字显示 → 断网 → 重进后历史完整，控制台无错误

## Comments

- 2026-07-30：真实链路诊断显示 `quick -> reasoning_effort=low` 的首正文约 5.7 秒；同模型使用 `thinking.type=disabled` 后约 1.4–1.8 秒。支付宝 `my.request` 无分片回调，当前客户端只能在完整 SSE 响应结束后回放。
- 2026-07-30：匿名竞品实测：出现文字后断网，前台流式展示中止；重新进入查看历史时回答完整。决定采用相同语义——对话轮次由 server-java 持有，客户端连接只是实时观察者。
- 2026-07-30：WebSocket 握手只通过 Authorization 请求头携带患者 JWT，沿用 `c_patient` scope 校验；禁止把令牌放入 URL 查询参数。
- 2026-07-30：WebSocket 不传原始 SSE 文本；客户端请求与服务端事件统一使用带 `type`、`request_id`、`data` 的结构化 JSON 信封，事件信封另带既有 SSE `event` 名称。
- 2026-07-30：对话轮次成为 server-java 持久化业务实体，PostgreSQL 是唯一事实源；`(patient_id, request_id)` 唯一约束防止重复追加消息和重复运行 Agent。Redis 对当前单实例、低频状态查询没有业务收益，本票不引入；运行任务与实时观察者只保存在进程内。
- 2026-07-30：当前模型实测不支持 `thinking.type=auto`（HTTP 400）。推理档位改为产品侧按场景选择：快速始终关闭思考；自动在普通对话/导诊关闭、复杂解读 high；深度始终 high。该决策修正 ADR-0004 的 `triage -> low`，避免默认自动档继续承担约 5–6 秒思考 TTFT。
- 2026-07-30：保留 `POST /api/c/chat` SSE 供 curl、诊断和非小程序客户端使用，但降为薄传输适配器；小程序只使用 WebSocket，两种入口共用 `ChatRoundService`、`request_id` 幂等和断连后继续执行语义。
- 2026-07-30：流式生成期间不提前展示免责声明；小程序收到最终 `message` 后才在完成态 AI 气泡显示契约免责声明。生成态必须有明确状态，最终消息与历史回放仍无例外携带免责声明；红线规则不显示。
- 2026-07-30：支付宝官方说明新发布小程序可能仅支持 WSS，HTTP/WS 不受支持。本地 `http://127.0.0.1` 开发环境因此直接使用同 `request_id` 的 SSE 验收模式，避免必失败的 `ws://` 请求污染控制台；HTTPS 环境仍使用页面级 WSS。server-java 的 JWT WebSocket 握手、`accepted` 后断连继续运行及历史持久化已用真实协议黑盒验证。
- 2026-07-30：真实模型复测第二组连续 5 次通过：quick 中位数 2.136 秒、最大 3.353 秒；auto 普通对话中位数 2.353 秒、最大 2.797 秒。深度档首 token 记录为 7.229 秒，不设阈值。
- 2026-07-30：C 端 Agent 移除禁忌工具与个性化用药决策，只保留通用药品知识解释并引导咨询医生或药师；server-java 的确定性禁忌检查仅保留在 B 端医生开方流程。红线症状规则仍在 C 端入口前置执行。移除禁忌工具后不再为等待该安全门而缓冲整轮模型文本，普通回答可单阶段流式输出。
- 2026-07-30：缩减范围：断线后不做跨轮自动重新附着。轮次继续执行并持久化，恢复已完成轮次的路径只有重进页面拉取对话记录（对齐竞品语义）。允许的自动恢复只有一种：同一 `request_id` 的传输降级——WebSocket 建连失败或轮次进行中断线时自动回退 SSE 继续接收本轮，不重跑、不生成新 ID；用户明确重试才创建新轮次。
- 2026-07-30：TTFT 验收线：真实模型快速/自动普通对话连续 5 次首 token 中位数不超过 3 秒、最大不超过 5 秒；深度档仅记录。确定性自动化测试要求 fake 首 token 经 server-java 转发到 WebSocket 的额外延迟不超过 100ms；日志记录 accepted/首事件/首 token/完成耗时，不含患者原文。
- 2026-07-30：定位支付宝开发者工具 WSS 建连失败根因：devtools（Electron 运行时）会把 `my.connectSocket` 的 `header` 参数值整体包一层字面双引号（如 `Authorization: "Bearer …"`），server-java 按严格语法解析令牌失败导致握手 401，客户端仅报笼统"连接失败"。诊断手段：本地同证书免鉴权 WSS 探针抓取原始握手字节。处置：小程序改经 `Authorization: Bearer …` 携带 JWT（即本票 checklist 的原始设计，替代 Sec-WebSocket-Protocol 子协议方案）；server-java `AuthFilter` 解析 Authorization 前剥离成对外层引号以兼容模拟器，对真机与标准客户端为 no-op。注意 `my.request` 的 header 无此引号问题。
- 2026-07-30：双轴审查修复批次：信封类型/轮次状态/ws 路径全部改经 `contracts/chat-realtime.json` 访问器消费；删除残留的 Sec-WebSocket-Protocol 认证通道（含 `ChatWebSocketProtocol` 与子协议用例），握手只认 `Authorization: Bearer`，URL 令牌依旧拒绝；`ChatService` 恢复票 33 出口语义（响应提交前失败走 HTTP 错误、提交后失败安静收尾）；卡片路径回归测试（多卡片按序逐张落库、落库失败显式失败不留悬空）移植到 `ChatRoundServiceTest`；清理小程序打字机死代码；ADR-0004 Status 行标注自动档映射被 ADR-0015 取代；`config.js` 提交值回退 `localhost`（真机调试临时改局域网 IP，不入库）。评审"llm.py 的 low 成死值"为误判：`app/agent/clinical.py` 仍消费 `reasoning_effort="low"`，Literal 保留。

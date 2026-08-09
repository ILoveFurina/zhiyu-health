# SSE 整段响应与真实对话流：从伪流式重放到 WebSocket 逐轮重试

2026-08-09 记录。C 端对话在 issue 70（AI 等待态/思维链）合入后被报告"AI 失去流式效果"：气泡长时间停留在等待动画，随后整段回复一次性出现。本文保留完整纠错过程：第一次误判为渲染队列合并，issue 81 又用 SSE 定时重放掩盖非流式事实，issue 82 恢复隧道下 WebSocket，issue 85 最终删除伪流式并修复一次失败永久毒化页面的问题。

相关实现：

- `miniprogram/utils/chat-stream.js`：页面级 WS 通道 + SSE 降级，本次唯一改动点
- `server-py/app/agent/runner.py`、`server-py/app/services/chat.py`：token/thinking 事件产出
- `server-java/.../service/ChatRoundService.java`、`controller/patient/chat/ChatWebSocketHandler.java`：中继与首帧鉴权
- `docs/engineering-notes/wss-and-windows-service-pitfalls.md` 第 6 节：cpolar 隧道剥头及首帧鉴权方案

## 1. 症状与第一次误判

issue 70 合入后用户反馈"AI 失去了流式的效果"。第一次修复（1ad9ed7）假设根因是"首个正文 token 到达后 `onBodyStart` 与正文追加连续两次全量 setData `messages`，被支付宝渲染队列合并"，改为单次 patch 原子完成。该改动本身是无害 hygiene（每 token 少一次全量 setData），但**没有恢复流式**——因为它不是真根因，且当时按用户明确要求免除了开发者工具实测，误判未被及时发现。

教训：实时链路的"体感回归"必须逐跳定位哪一跳不再流式，不能在某一跳上凭空假设。

## 2. 逐跳诊断（全部为实测证据）

请求链路共四跳：server-py 产出 → server-java 中继 → 端侧通道（WS/SSE）→ 页面渲染。逐跳钉住后，真根因只剩一个候选。

### server-py 产出：流式正常

`scripts/run-server-py.py` 起服后直连 `/api/agent/chat`（带 `X-Agent-Callback-Token`），逐行打到达时间戳：

```text
03:55:36.530 event: token / data: {"text": "头疼"}
03:55:36.593 event: token / data: {"text": "两天"}
03:55:36.662 event: token / data: {"text": "确实"}
... 每片间隔约 22ms
```

### server-java 中继：流式正常

mock-login 取患者 JWT 后 curl `-N` 打 `/api/c/chat`，token 以约 40ms 间隔逐片到达。WS 中继与 SSE 共用 `Sinks.Many` 逐事件 emit（`ChatRoundService.forward` → `RunningRound.emit`），`ChatWebSocketHandler` 一事件一帧，静态审阅无缓冲点。

注意干扰项：带明显挂号意图的提问会命中票 62 强制号源短路（meta → message → department_slots → done，无 token 流），属预期编排行为，不是流式故障；诊断时需用纯健康咨询类输入（如"高血压老人日常饮食应该注意什么"）走 Agent 流。

### 页面渲染逻辑：逐 token 累积正常

用真实 `pages/chat/index.js` 搭 Node harness（mock `Page`/`my`），模拟一轮事件序列：每次 `streamAssistantToken` 后 `messages` 中 content 逐 token 增长，每 token 恰好一次 `messages` setData + 一次 `anchorId` setData——与票 70 之前的渲染节奏一致。渲染合并假设至此证伪：若渲染队列合并是根因，票 70 前同样的节奏不可能流式。

### 端侧通道：唯一非流式路径

`chat-stream.js` 双通道：

- **WS（主通道）**：一帧一事件，天然逐片。
- **SSE 降级**：`streamSse` 用 `my.request` 拉取——**`my.request` 不支持增量读取响应体**，`success` 回调拿到的是整段 SSE 文本，随后 `parseSse(...).forEach(dispatchEvent)` 在**同一个 JS tick 内同步派发全部事件**。于是无论 LLM 流了多久，气泡都从等待态直接跳到全文。这正是用户看到的现象。

SSE 降级自票 34 起就是"功能完整、非流式"设计，平时不触发。issue 82 之前，真机经 cpolar 隧道时 WS 握手会失败：隧道代理重写升级请求并剥掉 `Authorization`，server-java 返回 401，端侧常见 `error: 8 / Invalid Sec-WebSocket-Accept`。本轮报告期间每轮对话因此落在 SSE 降级上，"流式丢失"与 issue 70 仅是时间重合。

## 3. 历史方案：SSE 降级按节奏重放（issue 85 已撤回）

服务端与 WS 链路不动（它们本来就是流式），只在端侧 SSE 降级路径把"整段到达"补回"逐片体感"：

- `parseSse` 之后不再同步 forEach 派发，改 `replaySseEvents` 按序重放：
  - `token` 逐片 20ms 定时下发（对齐实测 LLM 自然流速约 22ms/片）；
  - `thinking` 逐片 8ms（增量更小，避免长思考链把思考区拖得比正文还慢）；
  - 其余事件（meta/tool_start/tool_end/卡片/message/done 等）按原顺序即时下发，不额外延迟。
- **取消语义**：重放闭包钉住本轮对象，`done`/`error`/页面卸载（`finishRound`/`failCurrent`/`close` 均置空 `current`）后 `isAlive()` 为假即停，迟到 token 不会覆盖错误/完成终态，也不会 setData 到已卸载页面。
- WS 路径不受影响、不加节流——网络天然 pacing，人为延迟只会劣化。

该方案能制造逐字体感，但总时长 = 完整响应下载 + 重放时长；更严重的是，它会把响应完成后的 thinking 快照伪装成正在发生的思考，并把本地重放时间显示为思考用时。issue 85 判定这种表现不可接受，已删除实现；本节只保留为失败方案记录。

## 4. issue 81 当时的验证（历史记录）

- 真实 `createChatChannel` harness（mock `my` 使 connectSocket 失败走降级）：事件顺序 `fallback → meta → thinking×2 → token×3 → tool_start → tool_end → token×2 → message → done` 完整保持；token/thinking 到达间隔实测 9~32ms，不再单 tick 批量。
- 取消 harness：首个 token 后 `finishRound()`，后续 token/message/done 均未派发。
- 页面级 harness 回归：chat 页逐 token 渲染行为与修复前一致（SSE 重放只改事件到达节奏，不改页面逻辑）。
- 开发者工具/真机最终渲染无法在本环境自动化实测，合入后需人工确认一轮。

## 5. issue 82：首帧鉴权恢复隧道下真流式

截图再次出现"默认等待很久，最后 thinking 和正文一起蹦出"，Node 最小反馈环也证明 `my.request.success` 之前没有任何 thinking。这说明 SSE 重放只能改善完成后的呈现，不能改善 TTFT，也不能显示模型正在发生的思考。

最终修复将患者 JWT 从 WebSocket Upgrade header 移到连接内第一条消息：

1. `/api/c/chat/ws` 的 HTTP Upgrade 精确放行，但不因此建立患者身份；其他 HTTP API 的 `AuthFilter` 边界不变。
2. 客户端 `onOpen` 发送 `{type: "auth", data: {token}}`，JWT 不进入 URL 或 Upgrade header。
3. server-java 校验签名、有效期和 `scope=c_patient`，回复 `authenticated` 后才接受 `chat`；未认证、无效令牌、staff scope 和重复认证均确定性拒绝。
4. 客户端收到 `authenticated` 后才完成 `connect()` 并发送 `chat`；认证无回执超过 5 秒则关闭 WS 并降级 SSE，避免半开连接无限等待。

cpolar 仍会重建 Upgrade 并剥离自定义头，但会正常透传连接内消息，因此不再阻断聊天 WS。主路径恢复真实 `meta/thinking/token` 逐帧抵达；SSE 只保留为功能兜底。

## 6. 当前边界

- `my.request` 没有增量响应 API，SSE 降级只能等待完整响应。降级时明确显示等待完整回复，忽略 token/thinking 快照并一次投影最终 message；不再伪造流式或思考耗时。
- 主路径的 TTFT 由登录态就绪、WS 建连、首帧认证、server-java 受理和 server-py 首事件共同组成，不再额外等待整段模型响应。
- 开发者工具/真机需确认首个 `meta/thinking` 在最终 `message` 之前可见；该 GUI 验收由开发者执行。

## 7. 首帧鉴权改造引发的 Spring 启动装配回归

issue 82 首次提交为兼容旧单测，在 `ChatWebSocketHandler` 中同时保留了 4 参数生产构造器和 3 参数包级测试构造器。两者都未标注注入语义；Spring 遇到多个候选构造器后改为寻找无参构造器，最终导致 server-java 启动时报 `No default constructor found`。普通 handler 单测均直接 `new` 对象，已有 Web 切片测试又不会装配该普通组件，因此 736 项测试全绿仍未捕获。

修复不是增加无参构造器或仅添加注解，而是删除测试专用构造器，让组件保持唯一生产构造器；旧测试显式传入 mock `PatientTokenService`。同时新增 2 秒级 `AnnotationConfigApplicationContext` 回归测试，使用真实 Spring 构造器选择规则装配该组件。提交前除 handler 测试外，必须实际运行一次 `mvn -f server-java/pom.xml spring-boot:run` 并观察到 `Started ZhiyuApplication`，单纯 `mvn test` 不能替代启动烟测。

## 8. issue 85：一次 WS 失败让后续所有回复变成伪流式

issue 82 客户端仍保留 `websocketUnavailable` 页面级布尔值：任意 `connectSocket`、认证或传输错误都会置为 true，直到页面销毁。第一轮即使已通过 SSE 完成，第二轮 `connect()` 也会直接拒绝，根本不再尝试 WebSocket。真实 `chat-stream.js` 红测试稳定得到 `connectSocket attempts: actual=1, expected=2`，解释了“现在 AI 所有回复都变成假流式”。

最终行为：

- WS 错误只让**当前轮**降级；连接状态清理后，下一轮重新建连并发送 auth 首帧。
- 真 WS 路径继续逐帧投影 `meta/thinking/token`。
- SSE 完整响应中跳过 `token/thinking`，只投影 meta、工具/卡片、最终 message 与 done；等待态明确写“实时连接不可用，正在等待完整回复…”。
- cpolar 协议探针实测约 599ms 建连、约 731ms 收到 authenticated，chat 的 accepted/meta 也能实时到达，证明隧道不是本轮永久降级的根因。
- 诊断期间火山方舟五小时额度耗尽并返回 `AccountQuotaExceeded`，会造成 meta 后轮次失败；这是独立运行时状态，不得与传输真假混为一谈。

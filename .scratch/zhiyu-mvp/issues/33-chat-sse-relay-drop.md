# 33 — C 端 chat SSE 中继断流排查与修复

**What to build:** 定位并修复 C 端 `POST /api/c/chat` 经 server-java WebClient 中继 server-py `POST /api/agent/chat` 时 SSE 流提前断开的问题，使端侧稳定收到完整 `meta → token → message → done` 序列（含多轮工具调用的长对话）。

**Blocked by:** 无

**Status:** done

- [x] 复现并定位断流根因（嫌疑面：uvicorn StreamingResponse × LangGraph astream 交互、server-java reactor-netty 中继背压/超时、链路空闲超时配置）
- [x] 修复并补回归测试：中继链路流式稳定性测试（长对话 + 工具回调期间不断流）
- [x] ~~浏览器/小程序实测~~ curl 实测 C 端完整多轮对话流式到达 done，无控制台错误（用户确认 curl 验收，小程序侧由用户自测）

## Comments

- 2026-07-30（票 23 验收期间发现，与票 23 无关）：本地 server-java + server-py 连云数据库，C 端 chat 经 server-java 中继时客户端只收到 `meta` 事件，约 12 秒后连接断开。server-java 日志报 `reactor.netty.http.client.PrematureCloseException: Connection prematurely closed DURING response`（读取 server-py SSE 流期间），随后 `HttpMessageNotWritableException: No converter for LinkedHashMap with preset Content-Type 'text/event-stream'`；server-py（uvicorn）日志无任何异常。审计日志证明同期 Agent 实际在正常工作：断流后仍成功回调 `GET /api/agent/doctors/recommend` 等工具并返回 200。对照实验：同等负载经 fastapi TestClient 进程内直连 server-py `/api/agent/chat`，完整流式到达 `done`（两轮对话含 doctor_recommendations/doctor_slots/appointment 工具事件均正常）。首次观察到该现象时环境存在系统代理干扰（httpx 回调 localhost 被劫持 502），关闭代理并重启 server-py 后该干扰排除，断流仍稳定复现。复现载荷：`{"content":"帮我挂智愈市人民医院心血管内科林知远医生今天上午的号"}`（患者侧 mock-login token）。
- 2026-07-30（排查与修复）：定位出**两类断流源**，均已修复并复验：
  1. **主根因——`messages.kind VARCHAR(20) 溢出`**：契约 message_kinds 中 `doctor_recommendations`(22)/`hospital_recommendations`(24) 超长，首张卡片事件落库时 `ChatService.forwardAgentEvent` 抛 DataIntegrityViolationException 并穿透 reactor onNext，上游订阅被静默取消。原票日志的 PrematureCloseException 是该取消与本地异常的竞态表现。修复：`schema.sql` 加宽至 VARCHAR(32)（云库已用 `ALTER TABLE messages ALTER COLUMN kind TYPE VARCHAR(32)` 应用，保留演示数据；schema.sql 因 CREATE IF NOT EXISTS 不会自动生效存量库）；`ContractsConsistencyTest` 新增列宽守卫钉死。
  2. **工具回调失败穿透掐流**：langgraph ToolNode 默认只兜 ToolInvocationError，业务回调失败（409 售罄/无档案、后端不可用）的执行期异常穿透图，uvicorn 中途断连 → 中继 PrematureCloseException。修复：server-py `tools/business.py` 回调失败与模型臆造参数统一规整为模型可解释的错误文本（不投影卡片，由模型向用户解释）；禁忌检查失败保留"无法可靠检查不得推荐"安全语义。
  - **中继韧性（顺带修复）**：`ChatService` 转发路径捕获全部 RuntimeException 并幂等收尾（响应未提交→统一异常出口；已提交→安静 complete，消除 No converter 二次噪音）；emitter 超时 60s→300s（LLM 思考窗口无字节，60s 会误杀长对话）；emitter 完成/超时/错误回调接线取消上游订阅（设计文档 §8：断连需取消下游请求并结束资源，此前端侧断连后 Agent 空转）。
  - **SSE 链路日志（按用户要求增强）**：server-java `ChatService` 输出 relay start/complete/upstream failed/downstream broken/forward failed/timeout 及逐事件 DEBUG（conversationId、计数、耗时）；server-py 新增 `app/api/sse.py` 统一 SSE 出口日志（start/complete/cancelled by client/failed + 逐帧 DEBUG），新增 `app/core/logging.py` 接管 app.* logger（uvicorn 默认不管 root，INFO 曾被静默吞掉——正是原排查"server-py 无任何日志"的原因）。日志只记身份/档位/事件名/计数/耗时，不记患者原文。
  - **复验**：复现载荷经中继完整到达 done（44 事件/22.5s）；同会话多轮（含 40s 长对话、真实 409 无档案拒绝）流不中断、模型自然语言解释失败；server-java 193/193 测试 + spotless 过，server-py 57/57 测试 + ruff/mypy/lint-imports 过。
  - **遗留**：小程序 `chat-stream.js` 客户端超时 120s，更长对话需另行评估；云库其余存量环境若另有 messages 表需同步 ALTER。

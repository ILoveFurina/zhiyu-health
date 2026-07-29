# 33 — C 端 chat SSE 中继断流排查与修复

**What to build:** 定位并修复 C 端 `POST /api/c/chat` 经 server-java WebClient 中继 server-py `POST /api/agent/chat` 时 SSE 流提前断开的问题，使端侧稳定收到完整 `meta → token → message → done` 序列（含多轮工具调用的长对话）。

**Blocked by:** 无

**Status:** ready-for-agent

- [ ] 复现并定位断流根因（嫌疑面：uvicorn StreamingResponse × LangGraph astream 交互、server-java reactor-netty 中继背压/超时、链路空闲超时配置）
- [ ] 修复并补回归测试：中继链路流式稳定性测试（长对话 + 工具回调期间不断流）
- [ ] 浏览器/小程序实测 C 端完整多轮对话流式到达 done，无控制台错误

## Comments

- 2026-07-30（票 23 验收期间发现，与票 23 无关）：本地 server-java + server-py 连云数据库，C 端 chat 经 server-java 中继时客户端只收到 `meta` 事件，约 12 秒后连接断开。server-java 日志报 `reactor.netty.http.client.PrematureCloseException: Connection prematurely closed DURING response`（读取 server-py SSE 流期间），随后 `HttpMessageNotWritableException: No converter for LinkedHashMap with preset Content-Type 'text/event-stream'`；server-py（uvicorn）日志无任何异常。审计日志证明同期 Agent 实际在正常工作：断流后仍成功回调 `GET /api/agent/doctors/recommend` 等工具并返回 200。对照实验：同等负载经 fastapi TestClient 进程内直连 server-py `/api/agent/chat`，完整流式到达 `done`（两轮对话含 doctor_recommendations/doctor_slots/appointment 工具事件均正常）。首次观察到该现象时环境存在系统代理干扰（httpx 回调 localhost 被劫持 502），关闭代理并重启 server-py 后该干扰排除，断流仍稳定复现。复现载荷：`{"content":"帮我挂智愈市人民医院心血管内科林知远医生今天上午的号"}`（患者侧 mock-login token）。

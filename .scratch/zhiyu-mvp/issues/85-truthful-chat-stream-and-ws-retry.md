# 85 - 真实对话流与 WebSocket 逐轮重试

**What to build:** 撤回 issue 81 在完整 SSE 响应到达后的 token/thinking 定时重放。WebSocket 瞬时失败只影响当前轮次，下一轮必须重新建连和首帧鉴权，不得把页面级 channel 永久标为不可用。SSE 降级明确显示“等待完整回复”，忽略已失去实时语义的 token/thinking 快照，只投影最终 message、卡片和 done；不得据此展示思考过程或计算思考耗时。真实 WebSocket 路径继续逐帧投影 thinking/token。

**Blocked by:** 81 - 深度思考流与 quick TTFT 修复；82 - WebSocket 首帧鉴权恢复隧道下实时 thinking

**Status:** done

- [x] miniprogram：WS 瞬时失败不永久毒化 channel，下一轮重新 connect/auth
- [x] miniprogram：SSE 降级删除 token/thinking 定时重放，只投影完整响应快照
- [x] miniprogram：三处对话入口统一显示明确的非流式降级等待文案并清除 thinking 状态
- [x] 回归：第一轮 WS 失败后第二轮重新建连；SSE 不派发 token/thinking；WS 仍实时派发
- [x] 文档：记录永久熔断与伪流式根因，撤回 issue 81 重放结论
- [x] 开发者工具/真机验收由开发者执行
- [x] 票单置 done 前：README 依赖图 T85 节点加 `[x]`

## Comments

- 2026-08-09：当前 cpolar 实测健康：WSS 约 599ms 建连、约 731ms 收到 `authenticated`；真实 chat 在约 1.7s 逐帧收到 `accepted/meta`，证明隧道能透传连接内帧。
- 2026-08-09：真实 `chat-stream.js` 红测试复现：第一轮 `connectSocket.fail` 后，即使该轮 SSE 已完成，第二轮也不再调用 `connectSocket`（actual=1, expected=2）。根因是 `websocketUnavailable` 在任意错误后保持到页面销毁，使后续所有回复永久进入 SSE 重放。
- 2026-08-09：诊断时火山方舟返回 `AccountQuotaExceeded`（五小时额度耗尽），该运行时故障会在 meta 后终止轮次，但不解释此前“完整回复以假流式出现”；待额度恢复后由开发者做真机最终验收。
- 2026-08-09：修复后真实模块 harness 通过：第一轮瞬时失败走非流式快照且不派发 thinking/token；第二轮 `connectSocket` 调用数为 2，并按 `auth → chat` 后实时派发 thinking/token。五个改动 JS 文件 `node --check` 通过，fallback 气泡状态测试通过，`npm --prefix miniprogram ci` 通过。
- 2026-08-09：支付宝官方 `my.request` 当前参数与 RequestTask 仅提供完整 success/data 和 abort，没有 chunk 回调；官方仍支持 `my.connectSocket` 配合全局 onSocketOpen/onSocketMessage。故 SSE 无法诚实模拟实时增量，WebSocket 仍是唯一真实流式主路径。

# 82 - WebSocket 首帧鉴权恢复隧道下实时 thinking

**What to build:** 修复 cpolar 等会重建 WebSocket 升级请求、剥离 `Authorization` 导致 C 端永久落入整段 SSE 降级的问题。WebSocket 握手不再携带 JWT；连接后的第一条 `auth` 信封完成患者令牌校验，服务端返回 `authenticated` 后客户端才发送 `chat`。未认证、无效令牌和重复认证均不得受理对话。保持 HTTP API 的 AuthFilter 边界、SSE 降级与 request_id 幂等不变。

**Blocked by:** 34 - C端对话传输升级；70 - C端AI等待态重构与思维链展示；81 - 深度思考流修复与 quick TTFT 回归治理

**Status:** done

- [x] contracts：登记 auth/authenticated WebSocket 信封并同步双栈消费测试
- [x] server-java：仅放行 WebSocket HTTP upgrade，JWT 改由首帧校验
- [x] server-java：未认证 chat、无效 token、staff scope、重复 auth 均有确定性拒绝测试
- [x] miniprogram：onOpen 先发 auth，authenticated 后才 resolve connect/send chat；认证 5 秒无回执自动降级
- [x] 回归：连接成功时 thinking 可在 SSE success 前到达；连接/认证失败仍保持原 SSE 降级
- [x] 文档：更新 WSS/cpolar 与 SSE 降级笔记，不再声称 cpolar 下 WS 必然不可用
- [x] 开发者工具/真机验收由开发者执行
- [x] 票单置 done 前：README 依赖图 T82 节点加 `[x]`

## Comments

- 2026-08-09：截图中长时间保持默认 conservative 等待文案，完成后才出现“已深度思考（用时 3 秒）”。这证明模型运行前的 accepted/meta 未实时到达，实际落入 `my.request` SSE 整段响应路径；3 秒只统计完成后的本地 replay，不是模型真实思考耗时。
- 2026-08-09：Node 最小反馈环稳定复现：mock WebSocket 建连失败后，在 `my.request.success` 之前 `onThinking` 调用数恒为 0。支付宝 `my.request` 无增量响应 API，端侧无法从该通道提前获得真实 thinking。
- 2026-08-09：修复后真实 `chat-stream.js` 探针通过三条路径：成功连接严格发送 `auth → chat` 且 thinking 在 SSE success 前到达；网络失败降级；认证失败降级。JWT 未进入 URL/Upgrade header。
- 2026-08-09：回归通过：server-java 736 tests（10 skipped）、server-py 224 passed（2 skipped）；Spotless、Ruff、mypy、import-linter 全绿。未改 schema，无需重建演示库；开发者工具/真机视觉验收按约定交给开发者。

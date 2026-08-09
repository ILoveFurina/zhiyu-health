# 真机调试故障速查 Runbook

支付宝小程序真机调试/真机预览"又不能用了"时，按本文从快到慢排查。目标是 5 分钟内定位到层，不猜。

机制背景（理解这三点就够了）：

- **双地址运行时选择**（`miniprogram/utils/config.js`）：模拟器 localhost 探活成功走 `LOCAL`（`http://localhost:8080/api`），真机探活必失败走 `TUNNEL`（cpolar 隧道域名）。结果缓存在手机 storage，**每次启动探活只刷新缓存，本次启动沿用旧值**——所以改了地址要"进两次"或清缓存。
- **cpolar 隧道**：把本地 8080 暴露为受信任 HTTPS 域名。它会重建 WS 握手并剥掉自定义 header（见 `wss-and-windows-service-pitfalls.md` 第 6 节），因此票 82 起 WS 改为**连接建立后首帧鉴权**，隧道下 WS 可用；WS 失败时 `chat-stream.js` 自动降级 SSE。
- **token 有效期 12h**（server-java `zhiyu.patient-token-expire-minutes=720`）。`auth.js` 按 JWT `exp` 判过期（留 30s 余量）自动重新 mock-login；过期不会自愈的旧代码会带着烂 token 永久 401。

## 30 秒自检清单（按序执行，Git Bash）

```bash
# 1. 本地两个服务是否在监听（8080=server-java，8000=server-py）
netstat -ano | grep -E ':(8080|8000)\s.*LISTENING'

# 2. cpolar 是否在跑、当前分到的域名（免费版每次重启随机变）
curl -s http://127.0.0.1:4040/http/in | grep -oiE 'https?://[a-z0-9.-]+\.cpolar\.cn' | sort -u

# 3. config.js 的隧道域名是否等于上面的实际域名
head -12 miniprogram/utils/config.js | tail -3

# 4. 隧道通不通（两处都应 200）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/health
curl -s -o /dev/null -w '%{http_code}\n' https://<域名>/api/health
```

5. 对话链路冒烟（先拿 token，再打 SSE；**枚举必须合法**：effort ∈ auto/quick/deep，scenario ∈ triage/interpretation/preconsultation，非法值会 500 而非 400，曾因此误判服务端故障）：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/c/auth/mock-login \
  -H 'Content-Type: application/json' -d '{"nickname":"阿珍"}' | grep -oE '"token":"[^"]+"' | cut -d'"' -f4)
curl -s -N -X POST https://<域名>/api/c/chat -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"request_id":"probe-1","content":"你好","effort":"quick","scenario":"triage"}' | head -c 300
```

6. WS 握手（可选验证）：直连应 101 且 accept 为定值；经隧道 401 属预期（剥 header），不影响首帧鉴权流程。

```bash
curl -si http://127.0.0.1:8080/api/c/chat/ws -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Authorization: Bearer $TOKEN" | head -3
# 期望：HTTP/1.1 101 + Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

1–5 全过 → 服务端与隧道无恙，问题一定在手机侧（包旧/缓存旧），直接看"症状对照表"。

## 症状对照表

| 症状 | 最可能根因 | 处置 |
| --- | --- | --- |
| 全部"加载失败"，首次真机调试 | `config.js` 隧道域名是占位符或已失效 | 改成 cpolar 当前域名（本地改动勿提交）→ 重新编译 → 清缓存或进两次 |
| 全部"加载失败"，昨天还好好的 | 手机缓存的 token 过了 12h 有效期 | 已修（auth.js 过期自动重登）：**重新编译推包**即可，不用清缓存；旧包只能删小程序重进 |
| `error: 8 Invalid Sec-WebSocket-Accept response.` | cpolar 剥 upgrade header 后的 401 响应没有合法 accept | 票 82 起 WS 首帧鉴权后不应再出现；看到它说明**手机上跑的是旧包**，重新编译 |
| 控制台 `WebSocket 认证超时` / `WebSocket 建连失败，本轮降级且下轮重试` | 首帧鉴权 5s 内未完成（网络抖动或服务端刚重启） | 非致命，本轮走 SSE 降级，下轮自动重试；持续出现则按自检清单查服务 |
| 仅对话不可用，其余页面正常 | server-py 挂了或 LLM 链路异常 | 查 8000 端口与 `http://127.0.0.1:8000/api/health`；Windows 必须用 `uv run python scripts/run-server-py.py` 启动（AGENTS.md 第 6 节） |
| cpolar 重启后全部失效 | 免费版域名每次重启随机变化 | 按自检第 2 步取新域名改 `config.js`，重新编译 + 清缓存进两次 |
| 模拟器正常、真机请求全被拦 | 开放平台服务器域名白名单未配置 | 支付宝开放平台 → 小程序 → 开发设置 → 服务器域名（含 socket 合法域名）加上隧道域名 |
| 改了代码真机行为不变 | 忘了重新编译推包，手机跑旧包 | 真机调试必须重新编译；dev-up.ps1 只重启本地服务，不出包 |

## 手机侧两个反复踩的坑

1. **storage 缓存旧地址**：`runtimeApiBaseUrl` 缓存的是上次探活结果。改 `config.js` 后第一次启动仍用旧缓存（这次启动会失败属预期），探活写入新值，**退出再进第二次**才生效；勾选"清除缓存"编译可跳过第一次。
2. **token 12h 过期**（2026-08-09 实锤）：症状是"昨天可以，今天全挂"。修复后 `ensureLogin()` 按 `exp` 自动重登，用户无感；若行为异常先确认手机上的包含此修复（`miniprogram/utils/auth.js` 的 `isTokenUsable`）。

## 相关文档

- `wss-and-windows-service-pitfalls.md`：WSS/隧道/Windows 事件循环四类坑的完整挖因过程（本文的深读版）
- `ark-reasoning-and-chat-ttft-pitfalls.md`：思考流与 TTFT 排障
- `sse-fallback-streaming-replay.md`：SSE 降级不伪造流式的取舍

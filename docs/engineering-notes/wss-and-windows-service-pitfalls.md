# 本地联调踩坑记录：WSS 建连、Windows 事件循环与 psycopg 连接串

本文记录 2026-07-30 一次联调中挖出的四类坑。症状只有两个（小程序一开就报"WebSocket 建连失败"、对话全部"生成失败"），根因却分布在四个层次：支付宝开发者工具的 header 序列化、uvicorn 在 Windows 的事件循环选择、psycopg 连接串格式、以及一个死掉的进程。写给以后要在本地调试实时链路或重启三服务的人。

相关实现：

- `miniprogram/utils/chat-stream.js`：页面级 WebSocket 通道与 SSE 降级
- `server-java/.../config/AuthFilter.java`：JWT 鉴权与模拟器引号兼容
- `server-py/app/db/clients.py`：pgvector 连接（libpq 连接串归一化）
- `.scratch/application-local-wss.yml`：本地 WSS 覆盖配置（8443 + 自签证书）
- `.scratch/wss-probe.py`：本次诊断用的原始字节捕获探针（保留备用）

## 1. 支付宝开发者工具会给自定义 header 值包一层字面双引号

这是"WebSocket 建连失败"的真正根因。开发者工具（Electron 运行时）把 `my.connectSocket` 的 `header` 参数值整体 JSON 序列化后发出，线上实际是：

```text
Authorization: "Bearer faketoken456"
Sec-WebSocket-Protocol: "bearer, faketoken123"
```

引号是值的一部分。server-java 按严格语法解析（`startsWith("Bearer ")`、按 `bearer,` 前缀切分）必然失败，握手 401，而客户端只报笼统的"连接失败。开发者工具无法获取到具体的 socket 错误信息"。

注意两个反直觉的事实：

- `my.request` 的 header **没有**这个问题，只有 `connectSocket` 被污染；
- 证书、token 有效期、域名白名单当时全部正常，都是干扰项。

处置：小程序改经 `Authorization: Bearer …` 携带 JWT（即票 34 checklist 的原始设计）；`AuthFilter` 解析 `Authorization` 与 `Sec-WebSocket-Protocol` 前剥离成对外层引号，对真机与标准客户端是 no-op（`AuthFilterTest` 有对应用例）。

## 2. 字节级探针是定位客户端协议行为的唯一可靠手段

devtools 的错误消息刻意不含细节（"请通过真机调试获取"），但分层验证可以完全在本地完成，不需要真机：

1. curl 带有效 token 模拟握手 → `101`，证明服务端链路无恙；
2. curl 带假 token → `401`，证明鉴权拦截生效；
3. 本地起一个**同证书、免鉴权**的 WSS 探针（`.scratch/wss-probe.py`），让 devtools 连它，直接 dump 原始握手字节——引号问题就是这样现形的；
4. 探针收到 HTTP 握手这一事实本身，同时证伪了"devtools 不认自签证书"的假设（TLS 已完成）。

推论：凡遇"客户端报笼统网络错误"，先想清楚每一层该用什么探针验证，不要猜。本次依次排除了过期 token、域名白名单、自签证书三个合理但错误的假设。

## 3. Windows 上 uvicorn 单进程默认 ProactorEventLoop，psycopg 异步直接拒绝

uvicorn 的事件循环工厂在 Windows 上仅当 `use_subprocess=True`（即 `--reload` 或 `workers>1`）时才返回 `SelectorEventLoop`，单进程裸跑是 `ProactorEventLoop`；而 psycopg 3 的异步连接在 Proactor 上直接抛 `InterfaceError`。所以 `uv run uvicorn app.main:app --app-dir server-py`（不带 `--reload`）在这台机器上必然导致 pg 相关功能失败。

更糟的是 `--reload` 也不可靠：观察到 WatchFiles 打印 "Reloading..." 后新 server 进程从未起来（无任何 "Started server process" 日志），旧 worker 继续以旧代码服务——表现为"改了代码行为没变"。在 detached/后台环境尤甚。

当前可用姿势（后台单进程、钉死 Selector、无热重载）：

```bash
uv run python -c "
import asyncio, uvicorn
import uvicorn.loops.asyncio as uv_asyncio
uv_asyncio.asyncio_loop_factory = lambda use_subprocess=False: asyncio.SelectorEventLoop
uvicorn.run('app.main:app', app_dir='server-py', host='0.0.0.0', port=8000)
"
```

改动代码后需手动重启。若 server-py 行为异常或"进程莫名消失"，先看 8000 端口是否有监听，再怀疑其他。

## 4. psycopg 3.3 连接串的两个静默 bug

`server-py/app/db/clients.py` 的 `acquire_pg_connection` 长期存在两个问题，只因知识检索"任何异常降级返回空列表"的设计而被掩盖（`/api/health` 一直 500、RAG 一直静默走裸 LLM）：

- `.env` 的 `DATABASE_URL` 是 SQLAlchemy 风格 `postgresql+psycopg://…`，psycopg 不认该 scheme（会当成 key=value 串解析报"missing ="）。已加 `libpq_dsn()` 归一化为 `postgresql://`。
- `connect(..., timeout=5)` 的 `timeout` 不是 libpq 连接选项（psycopg 3.3 的 `connect()` 无此形参，多余 kwargs 并入 conninfo 后报错）。已改为 `connect_timeout=5`。

教训：降级逻辑会吞掉配置错误，健康检查必须真的探活——它这次以 500 的形式一直在大声报警，只是没人看。

## 5. ws 还是 wss

- 真机预览/真机调试/发布：**必须 WSS**。平台要求 socket 合法域名与受信任证书，明文 ws 与自签证书都过不去（票 34 引用的官方说明亦指出新发布小程序可能仅支持 WSS）。
- 本地模拟器：勾选"忽略域名/证书检查"后 `ws://127.0.0.1:8080` 理论上可用；票 34 当时记录 ws://"必失败"因而走 SSE，但按第 1 节的发现回看，那次失败很可能是同一个引号 header bug（握手 401），而非 ws 协议本身被拒——当时没有字节级证据。
- 建议：统一 WSS。本地 8443 覆盖配置（`.scratch/application-local-wss.yml` + 自签证书入系统信任库）一次配置长期有效；真机与云演示反正要 WSS，双协议只会让本地与真机行为分叉，排错成本远高于一张本地证书。

## 6. 真机预览走 cpolar 隧道：WS 升级请求会被剥掉自定义头（2026-08-07）

真机预览若不用 devtools 调试代理，可用 cpolar 隧道把本地 8080 暴露为受信任 HTTPS 域名（客户端与本地配置在 `.scratch/cpolar/`，已 gitignore）。实测（`.scratch` 回显服务器验证）：

- 普通 HTTP/HTTPS 请求头原样透传，登录、鉴权接口、SSE 对话流全部正常；
- 但 **WebSocket 升级请求由 cpolar 的 Go 客户端代为发起**（User-Agent 被改写为 `Go-http-client/1.1`、`Sec-WebSocket-Key` 重新生成），`Authorization` 等自定义头全部丢失，握手必然 401"未认证或令牌无效"。本地直连同一 token 握手 101，可据此区分是隧道行为而非服务端问题。
- 结论：cpolar 隧道下小程序对话实时通道不可用，依赖 `chat-stream.js` 的 SSE 降级（功能完整，非流式逐 token 体验）。需要隧道下完整 WS 时换 TCP 级透传的工具（如 ngrok），不要试图在 cpolar 配置里找开关。
- 真机侧报错特征：`my.connectSocket` 报 `error: 8 / Invalid Sec-WebSocket-Accept response.`——握手响应没有合法的 accept（隧道剥离 `Authorization` 后 server-java 返回 401，无 accept 头），看到这个错误串即可认定是隧道剥头而非证书/域名问题（2026-08-08 实测复现：同 token 直连 101、经隧道 401）。
- cpolar 免费版域名每次重启随机变化，换域名后只需改 `miniprogram/utils/config.js` 顶部的 `TUNNEL_API_BASE_URL`（该改动仅限本地，勿提交）。模拟器/真机的地址选择由 config.js 的 localhost 探活机制自动完成——devtools 会把 platform/brand 伪装成 iPhone，`getSystemInfo` 无法区分，不要再用它做环境判断。

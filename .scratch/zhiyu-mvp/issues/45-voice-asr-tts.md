# 45 - 语音双向（ASR 输入 + TTS 播报）

**What to build:** C 端对话页增加语音输入（支付宝 `my.getRecorderManager` 录音 -> server-java 转发 server-py -> 火山引擎 ASR -> 文字回端侧填输入框）与 AI 回复语音播报（按需点击触发 -> server-java 转发 server-py -> 火山引擎 TTS -> 二进制逐跳透传 -> 端侧播放/停止）。未配置/超时/失败时降级文字输入不阻塞演示。

**Blocked by:** 31 - 对话主干双栈化；40 - 对话 TTFT 与 WebSocket

**Status:** done（2026-08-08 真机验收通过：火山凭据 VOLC_ASR_API_KEY 已配置，真机按住说话识别真实语音链路打通；TTS 按契约决策保持关闭）

- [x] 新建 `contracts/voice.json`：完整骨架带占位（asr_enabled/asr_format null/asr_timeout_ms=10000/asr_max_duration_ms=60000/tts_enabled/tts_format null/tts_timeout_ms=15000/tts_voice null/error_codes/degrade_hint）
- [x] server-py `app/services/voice.py`：`AsrClient`/`TtsClient` 接口 + `FakeAsrClient`/`FakeTtsClient`；开通后加 `VolcAsrClient`/`VolcTtsClient`，按 `contracts/voice.json` 的 enabled + 环境密钥选实例
- [x] server-java `POST /c/asr`：multipart 音频转发 server-py `POST /api/agent/asr` 回文字
- [x] server-java `POST /c/tts`：按 message_id/text 转发 server-py `POST /api/agent/tts` 回二进制音频（`Content-Type: audio/mpeg` 等，按开通后格式）；另提供 `GET /c/tts`（query 传 text）供小程序 `my.downloadFile` 拉取音频临时文件播放
- [x] 端侧 `pages/chat/`：按住说话（`my.getRecorderManager`，识别结果填输入框可见可改不自动发）+ AI 气泡播放/停止（`my.createInnerAudioContext`）
- [x] 审计：server-java 入口记调用类型+参数类型+结果码/长度，不记音频与识别/合成文字原文（硬约束 5）；ASR/TTS 不进 `agent_call_logs` trace（见 ADR-0020）
- [x] 降级：契约开关（`asr_enabled`/`tts_enabled`）控制 UI 入口显示 + 运行时密钥检测兜底；未配置/超时/失败三情况降级文字，不阻塞演示
- [x] 测试：fake 覆盖正常/超时/未配置/失败；火山开通后真实 smoke（2026-08-08 真机验收通过）

## Comments

### 2026-08-08 - 火山 ASR 极速版接入（待用户凭据 + 真实 smoke）

- **端侧开关修复**：`utils/voice.js` 的 `ASR_ENABLED` 本地镜像未跟上票 58 契约点亮（仍为 false），导致对话页与在线问诊页按住说话按钮被隐藏；已对齐契约置 true，按钮回归（Fake 回落链路即可用）。TTS 按契约保持 false。
- **server-py 真实接入**：`VolcAsrClient` 落地——火山录音文件识别极速版 `POST /api/v3/auc/bigmodel/recognize/flash`，wav base64 内联、一次请求同步返回 `result.text`；成功判定读响应头 `X-Api-Status-Code`（20000000 成功；20000003/45000002 静音空音频 → VOICE_AUDIO_INVALID；其余 → VOICE_MODEL_FAILED），日志只记状态码与 X-Tt-Logid。新旧控制台两套鉴权头均支持（`X-Api-Key` 单头 / `X-Api-App-Key`+`X-Api-Access-Key`）。
- **凭据配置**：`Settings` 新增 `volc_asr_app_id` / `volc_asr_access_token` / `volc_asr_api_key` / `volc_asr_resource_id`（默认 `volc.bigasr.auc_turbo`），`.env.example` 同步占位；`_volcano_voice_key_ready` 改为真实判定，未配置仍回落 Fake（ADR-0029）。
- **契约**：`asr_format` 钉为 `wav`（端侧录音 wav 16k 单声道，极速版直收），双栈契约测试同步。
- **测试**：新增 `test_voice_volc.py`（MockTransport 覆盖两套鉴权头/payload/静音/失败/空白/超时/凭据判定/实例选择）；`test_voice_api.py` 经 `inject_key_ready` 钉死凭据判定，不随本机 .env 漂移。server-py 202 passed + ruff + mypy 全绿；server-java ContractsConsistencyTest + spotless 全绿。
- **IDE 模拟器坑（2026-08-08 实测定位）**：支付宝开发者工具模拟器不支持 `my.getRecorderManager`（[官方文档](https://opendocs.alipay.com/mini/api/recordermanager/start)："以真机调试结果为准"），`stop()` 后 `onStop` 不触发，端侧停在"识别中…"；两个语音页面已加 15s 看门狗兜底提示"当前环境不支持录音，请用真机调试或直接打字"（AGENTS.md Gotchas 已同步）。同期 curl 全链路（mock-login → `POST /api/c/asr` → 火山极速版）实测 3.3s 正常返回，后端链路无问题。
- **真机 UX 三修复（2026-08-08）**：①"按住"退化成"点两下"——真根因是按下瞬间 `voiceHint` 提示条在流内插入，把输入栏连同按钮**推离手指触点**，本次手势 `touchend` 丢失（第二轮修复；第一轮的"静态外壳"仅防按钮本体随手势重绘，救不了布局位移，保留为双保险）。提示条改绝对定位浮层（`.voice-hint-float` / consult `.voice-hint`，`bottom: 100%`，composer `position: relative`），出现/消失零位移。②屏幕中间"正在上传"大黑框是支付宝容器给 `my.uploadFile` 的原生 HUD，`uploadFile` 加 `hideLoading: true` 隐藏（已验证生效）。③耗时观测：server-java `voice-audit` 与 server-py `volc asr ok` 日志补 `elapsedMs/elapsed_ms`；实测 5s 音频全链路 1.6s（含火山推理），此前感知的"慢"主要来自交互 bug 导致的重复操作。
- **真机验收通过（2026-08-08，票置 done）**：用户真机调试确认按住说话→识别文字回填全链路可用。收尾定位的最后一个坑：按钮**中心不可按、右下角可按**——真机原生 `input` 同层渲染的命中框与可视位置存在小偏移，截获了按钮中心的触摸；触摸外壳 `.voice-btn-touch` 用 `padding: 24rpx; margin: -24rpx` 放大命中热区（不占布局）抵消偏移。三层根因与修复已记入 AGENTS.md Gotchas，诊断期埋的 `[voice]` console.log 已清除。

### 2026-08-04 - 骨架+fake 阶段落地

按设计澄清的"密钥开通前只做契约+server-py client 骨架+端侧 UI 骨架+fake 测试"策略先行落地，**未标 done**（火山语音服务开通前置未解除）：

- **契约**：`contracts/voice.json` 完整骨架带占位（enabled=false、格式字段 null、超时/最大时长占位值、error_codes、degrade_hint）；双栈 `Contracts` 接入（server-java `Voice` record + 访问器 + `ContractsConsistencyTest`；server-py `VoiceContract` + `test_contracts.py`）。
- **server-py**：`app/services/voice.py` 定义 `AsrClient`/`TtsClient` Protocol + `FakeAsrClient`/`FakeTtsClient` + `VolcAsrClient`/`VolcTtsClient` 占位（`NotImplementedError`）+ `VoiceService` 按 enabled+密钥选实例；`app/api/voice.py` 暴露 `POST /api/agent/asr`（multipart -> 文字）与 `POST /api/agent/tts`（JSON -> 二进制音频），均经 `AgentCallbackAuth` 鉴权；`test_voice_api.py` 覆盖正常/超时/未配置/失败/空音频/缺令牌六路径。
- **server-java**：`AgentClient` 加 `recognizeSpeech`/`synthesizeSpeech` 转发方法（契约开关前置、超时映射、稳定错误码）；`VoiceService` 做降级映射 + 脱敏审计（`voice-audit` logger：type/param/result/code|len，不记原文）；`VoiceController` 暴露 `POST /c/asr`（multipart）+ `POST /c/tts`（body）+ `GET /c/tts`（query，供小程序 downloadFile）；`VoiceControllerTest` + `VoiceServiceTest` 覆盖正常/未配置/超时/失败/空音频/空文本。
- **端侧**：`utils/voice.js` 封装 ASR（`my.uploadFile` multipart）+ TTS（`my.downloadFile` GET query）；`pages/chat` 加按住说话话筒（`my.getRecorderManager` 录音 -> 识别文字填输入框可见可改不自动发）+ AI 气泡播报/停止按钮（`my.createInnerAudioContext`，按需点击不自动播放）；契约开关（`asrEnabled`/`ttsEnabled`）控制入口可见性，骨架阶段两端 false -> 纯文字输入降级。
- **未做**：`VolcAsrClient`/`VolcTtsClient` 留 `NotImplementedError` 占位；真实火山 smoke 待开通。`.env` 字段名等开通后按实际凭据定（火山 ASR 与 TTS 可能用不同服务形态）。

回归：server-py 111 tests + ruff + mypy 全绿；server-java 301 tests + spotless 全绿。

## Comments

### 2026-08-03 - grill-with-docs 设计澄清

原票 20（情感化包）拆为 43/44/45 三票，本票承接原票 20 的"语音输入（ASR）"+"AI 回复 TTS 语音播报"两项。决策与 checklist 同等约束力：

- **策略**：契约先行 + 分层实现。密钥开通前只做契约+server-py client 骨架+端侧 UI 骨架+fake 测试（不依赖真实密钥，可先行）；开通后接真实火山 SDK/HTTP 跑通端到端标 done；始终不开通则停在此阶段，演示时语音入口降级为文字输入（对齐票 20"未配置/失败时降级为文字输入且不阻塞演示"）。
- **火山语音开通前置**：需在火山引擎控制台开通 ASR/TTS 服务并取凭据，**只有用户能做**（需用户火山账号）。开通前无法验收、最多骨架+fake，不能标 done。`.env` 字段名等开通后按实际凭据定（火山 ASR 与 TTS 可能用不同服务形态：一句话识别 vs 流式 ASR、标准 TTS vs 大模型 TTS）。
- **ASR 数据流**：端 `my.getRecorderManager` 录音 -> server-java（入口、鉴权、审计）-> server-py（调火山 ASR）-> 文字回 server-java -> 端把文字填入输入框（可见可改、不自动发）-> 用户确认后走正常 `startRound`。识别结果对用户可见可改（ASR 可能有错字），对话流入口统一（都从端侧 `startRound` 进），不在 server-java 开"语音直入对话"旁路。
- **TTS 数据流**：按需点击触发（不自动播放，医疗场景打扰、公共场合、按需省调用）、整条回复一次合成（不分段，MVP 简单）、独立 HTTP 拉取（`POST /c/tts`，不污染 WS JSON 信封、不 base64 膨胀、与 ASR 对称）。PRD"逐跳返回"理解为 server-py->server-java->端逐跳透传二进制，不必是 WS。
- **录音格式/传输方式/音频格式**：取决于火山 ASR/TTS 产品形态，等开通后钉入 `contracts/voice.json`（`asr_format`/`tts_format`/`tts_voice` 当前留 null）。火山 endpoint 不进契约（server-py 内部实现细节）。
- **审计/脱敏**：ASR/TTS 音频全程内存流转、不持久化、不落日志（对齐票 12 视觉管道"原始文件处理完即清理"先例）。审计只记调用类型+参数类型+结果码/长度，**不记音频与识别/合成文字原文**。ASR 识别文字一旦作为消息发出，按现有对话消息规则处理（脱敏摘要、trace 不记原文）。
- **trace 归属**：ASR/TTS 不进 `agent_call_logs`（非 LangGraph 工具循环内调用），仅 server-java 入口审计。详见 ADR-0020。
- **契约骨架**：`contracts/voice.json` 用方案 2 完整骨架带占位（结构一次性钉死，开通后只填值不改结构、不双栈二次发版）；`ContractsConsistencyTest` 先钉字段集合存在，开通后钉值非 null。
- **client 形态**：server-py `app/services/voice.py` 定义 `AsrClient`/`TtsClient` 接口（`asr(audio_bytes)->str` / `tts(text)->bytes`），骨架阶段 Fake 实现返回固定值，真实实现开通后加 Volc 实现，按 enabled+密钥选实例（与 vision interpreter 可替换模型适配层同构）。
- 不新增 CONTEXT.md 术语（ASR/TTS/语音输入/播报为通用概念，非本项目特有 ubiquitous language）；新增 ADR-0020（ASR/TTS 不进 trace 决策）。

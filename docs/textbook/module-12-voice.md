# 模块12：语音输入 / 输出

## 业务概述

语音双向（ASR 语音识别输入 + TTS 语音合成播报，票 45 / 58，ADR-0020）是 C 端对话与在线问诊的辅助输入通道：患者按住说话录音，经 server-java 鉴权审计后转发 server-py 调火山 ASR，识别文字回填输入框（可见可改、不自动发）；TTS 为按需点击播报 AI 回复。当前契约钉死 `asr_enabled=true`、`tts_enabled=false`：火山凭据未配置时 server-py 回落 Fake 固定文本，演示链路完整。ASR/TTS 不在 LangGraph 工具循环内、不进 agent_call_logs trace，未配置/超时/失败三种情况一律降级为文字输入，不阻塞演示。

## 业务流程

1. 端侧（当前仅 consult/preconsult 使用 mixin）：患者按住“说话”按钮，`onVoiceTouchStart` 惰性获取 `my.getRecorderManager` 单例，以 wav / 16kHz / 单声道开始录音，提示条显示“松开发送识别”。
2. 松手触发 `onVoiceTouchEnd`：提示条切到“识别中…”，同时启动 15s 看门狗；随后调 `recorder.stop()`。真机 `onStop` 到达即清掉看门狗；模拟器 `onStop` 永不触发，由看门狗兜底提示“请用真机调试或直接打字”。
3. `onStop` 回调里若未划出取消（`_voiceCancelled`），端侧调 `recognizeSpeech`：`my.uploadFile` 把录音临时文件以 multipart POST 到 server-java 的 `/api/c/asr`（带患者 JWT，`hideLoading: true` 隐藏容器原生大黑框）。
4. server-java `VoiceController` 校验音频非空，`VoiceService.recognize` 调 `AgentClient.recognizeSpeech`，`VoiceAgentApi` 先检查契约 `asr_enabled`，再以 multipart 转发 server-py 的 `/api/agent/asr`，请求头带 `X-Agent-Callback-Token`。
5. server-py `app/api/voice.py` 经 `AgentCallbackAuth` 校验回调令牌，从 `app.state.voice_service` 取 ASR 客户端：`enabled=false` 用 Disabled 占位抛 `VOICE_UNCONFIGURED`；凭据未就绪回落 `FakeAsrClient`（固定文本“我头疼两天了，该挂什么科”）；凭据就绪用 `VolcAsrClient` base64 内联上送火山录音文件识别极速版。
6. 识别文字逐跳返回：server-py `AsrResponse{text}` → server-java 审计落脱敏日志（只记 textLen / 耗时 / 结果码）→ 端侧 `setData` 回填 `inputValue` 并点亮发送按钮；患者可编辑后手动发送，语音不落任何消息类型。
7. 失败链（任一环节）：`VoiceError` 携契约稳定错误码 → server-py 映射 HTTP 状态 → server-java `VoiceAgentApi.mapError` 白名单过滤后抛 `VoiceAgentException` → `VoiceService` 出口统一映射为 `ApiException`（文案用契约 `degradeHint`）→ 端侧提示“语音识别失败，请直接打字”。
8. TTS（当前 `tts_enabled=false`，骨架保留）：端侧按需点击 → `my.downloadFile` GET `/api/c/tts?text=...` → server-java → server-py `/api/agent/tts` → 音频字节逐跳透传 → `InnerAudioContext` 播放。选 `downloadFile` 是因为 `InnerAudioContext` 要文件路径，`my.request` 的 arraybuffer 无法直接喂给音频上下文。

## 代码地图

| 层 | 职责 | 文件路径 |
| --- | --- | --- |
| 端侧封装 | ASR/TTS HTTP 封装与契约开关本地镜像 | `miniprogram/utils/voice.js` |
| 端侧 mixin | 按住说话手势、录音生命周期、15s 看门狗 | `miniprogram/utils/voice-input.js` |
| server-java controller | C 端入口校验与装配（零业务逻辑） | `server-java/src/main/java/com/zhiyu/health/controller/patient/chat/VoiceController.java` |
| server-java service | 转发编排、脱敏审计日志、出口统一降级文案 | `server-java/src/main/java/com/zhiyu/health/service/chat/VoiceService.java` |
| server-java agentclient | 契约开关前置检查、HTTP 转发、错误码白名单映射 | `server-java/src/main/java/com/zhiyu/health/agentclient/VoiceAgentApi.java` |
| server-py api | ASR/TTS 内部接口（回调令牌鉴权） | `server-py/app/api/voice.py` |
| server-py services | Protocol seam + Disabled/Fake/Volc 三实现选择 | `server-py/app/services/voice.py` |
| server-py schemas | 内部 HTTP 契约（AsrResponse / TtsRequest） | `server-py/app/schemas/voice.py` |
| 契约 | 开关、格式、超时、错误码、降级文案单一事实源 | `contracts/voice.json` |

## 核心代码走读

### 12.1 端侧开关与上传封装（voice.js）

`miniprogram/utils/voice.js:18-21` 是契约开关的端侧本地镜像——小程序读不了契约 JSON，契约变更须手工同步：

```js
// 本地镜像 contracts/voice.json 的 enabled 开关（契约变更须同步更新）：
// asr_enabled=true（票 58 点亮，Fake 阶段识别文字回填输入框）；tts_enabled 保持 false
const ASR_ENABLED = true
const TTS_ENABLED = false
```

上传在 `miniprogram/utils/voice.js:52-63`：

```js
function recognizeSpeech({ filePath }) {
  return new Promise((resolve, reject) => {
    my.uploadFile({
      url: `${apiBaseUrl}/c/asr`,
      filePath,
      fileName: 'audio',
      fileType: 'audio',
      // 隐藏支付宝容器原生“正在上传”大黑框 HUD（hideLoading 为容器参数，不识别的版本会忽略）；
      // 端侧自绘 voiceHint 提示条代替
      hideLoading: true,
      headers: { Authorization: `Bearer ${getToken()}` },
      timeout: 30000,
```

两个教学点：一是 `hideLoading: true` 是真机踩坑产物——`my.uploadFile` 默认会弹容器原生“正在上传”大黑框，端侧改用自绘提示条；二是录音临时文件路径直接交给 `uploadFile`，音频不经端侧转 base64。

### 12.2 模拟器 onStop 不触发的 15s 看门狗（voice-input.js）

支付宝开发者工具模拟器不支持 `my.getRecorderManager`（官方文档注明以真机为准）：`stop()` 后 `onStop` 可能永不触发，页面会永久卡在“识别中…”。`miniprogram/utils/voice-input.js:53-62` 的兜底：

```js
onVoiceTouchEnd() {
  if (!this.data.recording) return
  this.setData({ recording: false, voiceHint: '识别中…', voiceHintError: false })
  // IDE 模拟器不支持录音 API（支付宝官方文档：以真机为准），onStop 可能永不触发；
  // 看门狗兜底避免“识别中”卡死，真机上 onStop 到达即清除本定时器
  this._voiceWatchdog = setTimeout(() => {
    this.setData({ voiceHint: '当前环境不支持录音，请用真机调试或直接打字', voiceHintError: true })
  }, 15000)
  if (this._recorder) this._recorder.stop()
},
```

真机路径在 `onStop` 里（`voice-input.js:18-26`）第一件事就是 `clearTimeout(this._voiceWatchdog)`，所以看门狗只在模拟器或异常环境下真正触发。配套细节：录音 <1s 同样不触发 `onStop`，改走 `onError`（error:7）；`getRecorderManager` 单例只注册一次监听（`ensureRecorder` 里 `if (this._recorder) return`）；`onUnload` 必须调 `clearVoiceTimers()` 清掉悬挂定时器。结论：语音链路验收必须真机调试，模拟器表现不能作为验收依据。

### 12.3 server-java：入口校验、审计与出口降级（VoiceController / VoiceService）

controller 只做校验与装配，`server-java/.../controller/patient/chat/VoiceController.java:39-46`：

```java
@PostMapping("/asr")
public VoiceService.AsrResult recognize(
        @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, MultipartFile audio) {
    if (audio == null || audio.isEmpty()) {
        throw new com.zhiyu.health.config.ApiException(422, "VOICE_AUDIO_INVALID");
    }
    return service.recognize(audio, patientId);
}
```

`VoiceService.recognize`（`server-java/.../service/chat/VoiceService.java:41-57`）承担两件事：转发与审计：

```java
String text = agentClient.recognizeSpeech(audio);
// 审计只记结果码 + 识别文字长度 + 耗时 + 患者标识，不记原文（硬约束 5）
// elapsedMs 用于区分慢在上传/转发还是慢在火山推理（对照 server-py 侧耗时日志）
auditLog.info(
        "voice type=asr param=audio result=success patientId={} textLen={} elapsedMs={}",
        patientId,
        text.length(),
        System.currentTimeMillis() - startedAt);
return new AsrResult(text);
```

注意审计只记 `textLen` 不记识别原文（硬约束 5：Agent 相关日志不记患者敏感原文），`elapsedMs` 则用来和 server-py 侧的火山耗时日志对照定位慢点。出口处（同文件 L50-57）把 `VoiceAgentException` 统一映射为 `ApiException`，文案固定用契约 `degradeHint`——用户永远看到统一的降级提示“语音功能暂不可用，已切换为文字输入”，而不是 server-py 或火山的原始错误。

### 12.4 server-java 转发层：契约开关前置与错误码白名单（VoiceAgentApi）

`server-java/.../agentclient/VoiceAgentApi.java:36-40` 在转发前先查契约开关，未点亮直接 503，不打 server-py：

```java
String recognize(MultipartFile audio) {
    Contracts.Voice voice = contracts.voice();
    if (!voice.asrEnabled()) {
        throw failure(UNCONFIGURED, 503);
    }
```

错误映射在 `VoiceAgentApi.java:105-127`，是稳定错误码的关键防线：

```java
private AgentClient.VoiceAgentException mapError(WebClientResponseException error) {
    String code = null;
    try {
        code = objectMapper
                .readTree(error.getResponseBodyAsString())
                .path("detail")
                .path("code")
                .asText(null);
    } catch (Exception ignored) {
        // 只提取白名单码，不记录音频或识别原文。
    }
    if (!errorCodes.contains(code)) {
        code = error.getStatusCode().value() == 504 ? MODEL_TIMEOUT : AGENT_UNAVAILABLE;
    }
```

`errorCodes` 集合来自 `contracts/voice.json` 的 `error_codes` 白名单（L33 `Set.copyOf(contracts.voice().errorCodes())`）：server-py 返回的码不在白名单里就一律折叠为 `VOICE_MODEL_TIMEOUT` / `VOICE_AGENT_UNAVAILABLE`。这保证暴露给端侧的码集合永远受契约控制，server-py 内部实现变化不会泄漏出新码。超时上限 `asr_timeout_ms=10000` 同样来自契约（L66），且有意设计成 server-py 内层超时先触发、server-java 转发超时后兜底。

### 12.5 server-py：Protocol + Fake 的 seam 设计（services/voice.py）

这是本模块最值得讲的设计。`server-py/app/services/voice.py:39-48` 用 `Protocol` 定义 seam，不依赖任何具体实现：

```python
class AsrClient(Protocol):
    async def asr(self, audio_bytes: bytes, *, audio_format: str | None) -> str:
        """录音字节 -> 识别文字。失败抛 VoiceError。"""
        ...
```

三个实现各司其职：`FakeAsrClient`（L51-59）返回固定文本“我头疼两天了，该挂什么科”，还记录 `self.calls` 供测试断言调用参数；`_DisabledAsrClient`（L74-78）任何调用即抛 `VOICE_UNCONFIGURED`；`VolcAsrClient`（L86 起）是真实火山适配，构造函数接受可注入的 `httpx.AsyncBaseTransport`，测试用 `MockTransport` 替换真实网络。选择逻辑在 `VoiceService.asr_client()`（L215-227）：

```python
def asr_client(self) -> AsrClient:
    if self._asr is not None:
        return self._asr
    contract = get_contracts().voice
    if not contract.asr_enabled:
        return _DisabledAsrClient()
    if not self._is_key_ready():
        return FakeAsrClient()
    if self._lazy_volc_asr is None:
        from app.config import get_settings

        self._lazy_volc_asr = LazyDelegate(lambda: VolcAsrClient(get_settings()))
    return self._lazy_volc_asr.get()
```

三级决策：测试注入优先（`inject_asr` / `inject_key_ready` 钉死判定，测试结果不随本机 `.env` 漂移）→ 契约开关 → 运行时凭据就绪与否。好处是演示环境没有火山密钥也能跑通完整链路（Fake 固定文本回填可编辑输入框），凭据配上后零代码改动切到真实 ASR。`VolcAsrClient` 内部（L135-157）成功与否看响应头 `X-Api-Status-Code` 而非 body，日志只记状态码 / logid / 耗时 / 音频字节数，绝不记识别文字原文。

### 12.6 工具调用：为什么语音不在 LangGraph 工具循环里

本项目其他 AI 模块（对话、导诊等）的工具定义点在 `server-py/app/tools/`（如 `knowledge.py`、`department.py` 的 `@tool` 函数），在 `app/agent/runner.py` 注册进 LangGraph 图，业务写入经 `app/tools/callback.py` 的 `BusinessCallbackClient` 带 `X-Agent-Callback-Token` 回调 server-java。**语音模块刻意不走这条路**（ADR-0020）：`app/tools/` 下没有任何语音 `@tool` 函数，`runner.py` 不注册语音工具，ASR/TTS 是对话发起前/完成后的独立 HTTP 请求，不是 Agent 工具。

它复用的只有同一套回调鉴权机制。server-py 侧入口 `app/api/voice.py:20-25`：

```python
@router.post("/asr", response_model=AsrResponse)
async def asr(
    request: Request,
    files: Annotated[list[UploadFile], File()],
    _: AgentCallbackAuth,
) -> AsrResponse:
```

`AgentCallbackAuth` 在 `app/api/deps.py:9-23`，用 `secrets.compare_digest` 校验 `X-Agent-Callback-Token`；server-java 侧在 `AgentClient.java:38` 把这个令牌设为 WebClient 默认头，承接接口就是 `VoiceAgentApi` 转发的 `/api/agent/asr` 与 `/api/agent/tts`。端侧不直连这两个接口——一律由 server-java 鉴权审计后转发。教学上这是一个很好的对照案例：同样的鉴权 seam，一个用于 LangGraph 工具循环内的业务回调，一个用于循环外的独立语音请求；判断标准是“是不是 Agent 在推理过程中自主发起的工具调用”。

## 契约与 ADR

- `contracts/voice.json`：语音双向的单一事实源——`asr_enabled=true` / `tts_enabled=false`、`asr_format=wav`、`asr_timeout_ms=10000`、`error_codes` 白名单（`VOICE_UNCONFIGURED` / `VOICE_AUDIO_INVALID` / `VOICE_MODEL_TIMEOUT` / `VOICE_MODEL_FAILED`）与统一降级文案 `degrade_hint`；端侧 `utils/voice.js` 是它的本地镜像，须手工同步。
- `docs/adr/0020-asr-tts-not-in-agent-trace.md`（ASR/TTS 调用不进 agent_call_logs trace）：ASR/TTS 不在 LangGraph 循环内、不是 Agent 工具，仅 server-java 入口审计记脱敏日志，音频全程内存流转不持久化。
- `docs/adr/0029-online-consultation-media-messages.md`（在线问诊交流媒体消息：患者图片 + 语音输入，复用 AI 对话模块能力）：点亮 `asr_enabled` 的决策出处，并确定“enabled 但无火山密钥 → Fake 固定文本回落”的分支，语音只作输入通道、不落消息类型。

## 讲解提示

- **强调“seam 先行”**：Protocol + Disabled/Fake/Volc 三实现 + 运行时凭据判定，让契约开关、演示环境、测试环境、生产环境四种形态共用同一条代码路径。常见提问“为什么不直接 if/else 散落在各处”——答案要点：seam 集中在一处选择函数（`asr_client()`），调用方（api 层）完全无感知，测试注入点也只有 `inject_*` 三个口子。
- **看门狗是“防御不可能事件”的范例**：学生常问“15s 是不是拍脑袋”。答案要点：契约 `asr_timeout_ms=10000`，端侧上传 timeout 30s，看门狗 15s 介于两者之间——真机上 `onStop` 正常到达会先清掉它，它只在模拟器/异常环境兜住 UI 不卡死；这同时解释了为什么语音验收必须真机调试（模拟器 `stop()` 后 `onStop` 不触发、录音 <1s 走 `onError`）。
- **错误码白名单的收敛语义**：从 `mapError` 讲“对外暴露的错误码集合必须受契约控制”——server-py 内部可以抛出任意细分原因，过了 server-java 转发层只剩白名单内的稳定码，端侧只需按有限码做降级。
- **对照提问“为什么语音不进 agent_call_logs”**：答案要点即 ADR-0020——trace 记的是 LangGraph 工具循环内 `tool_start`/`tool_end` 配对，ASR 是输入方式、TTS 是输出呈现，塞进去会污染工具调用语义且要改 schema/白名单/contracts；调试看 server-java 入口脱敏日志即可。

> 返回目录：[docs/textbook/README.md](./README.md)

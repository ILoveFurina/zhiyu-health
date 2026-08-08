"""语音双向客户端 seam（票 45，ADR-0020）：ASR 识别与 TTS 合成的可替换适配层。

与 vision interpreter 同构：定义 Protocol（AsrClient/TtsClient），契约 enabled=false 时
Disabled 占位抛 VOICE_UNCONFIGURED；enabled=true 时按运行时凭据选 Volc 实现或回落 Fake
固定文本（票 58，ADR-0029）。ASR 用火山录音文件识别极速版（HTTP JSON 一次请求同步返回）；
VolcTtsClient 仍为开通后补全的占位。ASR/TTS 不在 LangGraph 循环内，不进 agent_call_logs
trace；音频全程内存流转不持久化（对齐票 12 视觉管道"原始文件处理完即清理"先例）。

未配置/超时/失败三情况一律抛 VoiceError（携带稳定错误码），调用方降级为文字，不阻塞演示。
"""

import base64
import logging
import time
import uuid
from collections.abc import Callable
from typing import Protocol

import httpx

from app.config import Settings
from app.core.contracts import get_contracts
from app.core.lazy import LazyDelegate

logger = logging.getLogger("app.services.voice")

# 火山录音文件识别极速版：endpoint 属 server-py 内部实现细节，不进契约（票 45 决策）
_VOLC_ASR_FLASH_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"


class VoiceError(RuntimeError):
    """语音调用失败：携带契约 error_codes 中的稳定码，供 server-java 出口映射。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class AsrClient(Protocol):
    async def asr(self, audio_bytes: bytes, *, audio_format: str | None) -> str:
        """录音字节 -> 识别文字。失败抛 VoiceError。"""
        ...


class TtsClient(Protocol):
    async def tts(self, text: str) -> bytes:
        """合成文字 -> 音频字节。失败抛 VoiceError。"""
        ...


class FakeAsrClient:
    """骨架阶段固定返回值；不依赖真实密钥，可先行测试。"""

    def __init__(self) -> None:
        self.calls: list[tuple[bytes, str | None]] = []

    async def asr(self, audio_bytes: bytes, *, audio_format: str | None) -> str:
        self.calls.append((audio_bytes, audio_format))
        return "我头疼两天了，该挂什么科"


class FakeTtsClient:
    """骨架阶段返回固定占位音频（非真实音频，仅用于链路测试）。"""

    def __init__(self) -> None:
        self.calls: list[str] = []

    async def tts(self, text: str) -> bytes:
        self.calls.append(text)
        # 占位字节：开通后由火山 TTS 产出真实音频，格式由 contracts/voice.json tts_format 钉死
        return b"FAKE-TTS-AUDIO"


class _DisabledAsrClient:
    """契约未启用时的占位：任何调用即抛 VOICE_UNCONFIGURED。"""

    async def asr(self, audio_bytes: bytes, *, audio_format: str | None) -> str:
        raise VoiceError("VOICE_UNCONFIGURED", "语音识别未配置")


class _DisabledTtsClient:
    async def tts(self, text: str) -> bytes:
        raise VoiceError("VOICE_UNCONFIGURED", "语音合成未配置")


class VolcAsrClient:
    """火山录音文件识别极速版适配（票 45）：wav base64 内联上送，一次请求同步返回 result.text。

    成功与否看响应头 X-Api-Status-Code 而非 body（20000000 成功；20000003 静音、45000002
    空音频），失败排障依赖 X-Tt-Logid。日志只记状态码与 logid，绝不记音频与识别文字原文
    （硬约束 5）。60s/16k/wav ≈ 2MB base64 内联，远低于官方 20MB 建议上限。
    """

    def __init__(
        self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None
    ) -> None:
        self._settings = settings
        # 测试经 MockTransport 替换真实火山调用；生产为 None 走真实网络
        self._transport = transport

    async def asr(self, audio_bytes: bytes, *, audio_format: str | None) -> str:
        settings = self._settings
        headers = {
            "Content-Type": "application/json",
            "X-Api-Resource-Id": settings.volc_asr_resource_id,
            "X-Api-Request-Id": uuid.uuid4().hex,
            "X-Api-Sequence": "-1",
        }
        # 新旧控制台两套鉴权：新版单 X-Api-Key；旧版 X-Api-App-Key + X-Api-Access-Key
        if settings.volc_asr_api_key:
            headers["X-Api-Key"] = settings.volc_asr_api_key
        else:
            headers["X-Api-App-Key"] = settings.volc_asr_app_id
            headers["X-Api-Access-Key"] = settings.volc_asr_access_token
        payload = {
            "user": {"uid": settings.volc_asr_app_id or "zhiyu-demo"},
            "audio": {
                "format": audio_format or "wav",
                "data": base64.b64encode(audio_bytes).decode("ascii"),
            },
            "request": {"model_name": "bigmodel", "enable_itn": True, "enable_punc": True},
        }
        # 超时沿用契约 asr_timeout_ms，保证 server-py 内先于 server-java 转发超时触发
        timeout_s = get_contracts().voice.asr_timeout_ms / 1000
        started_at = time.monotonic()
        try:
            async with httpx.AsyncClient(timeout=timeout_s, transport=self._transport) as client:
                response = await client.post(_VOLC_ASR_FLASH_URL, json=payload, headers=headers)
        except httpx.TimeoutException as exc:
            raise VoiceError("VOICE_MODEL_TIMEOUT", "火山 ASR 调用超时") from exc
        except httpx.HTTPError as exc:
            raise VoiceError("VOICE_MODEL_FAILED", f"火山 ASR 网络错误（{type(exc).__name__}）") from exc
        status = response.headers.get("x-api-status-code", "")
        logid = response.headers.get("x-tt-logid", "")
        elapsed_ms = int((time.monotonic() - started_at) * 1000)
        if status != "20000000":
            logger.warning("volc asr failed status=%s logid=%s elapsed_ms=%d", status, logid, elapsed_ms)
            if status in ("20000003", "45000002"):
                raise VoiceError("VOICE_AUDIO_INVALID", "静音或空音频")
            raise VoiceError("VOICE_MODEL_FAILED", f"火山 ASR 失败（{status or response.status_code}）")
        # 耗时日志只记毫秒数/音频字节数/logid，不记识别文字原文（硬约束 5）
        logger.info("volc asr ok elapsed_ms=%d audio_bytes=%d logid=%s", elapsed_ms, len(audio_bytes), logid)
        try:
            text = (response.json().get("result") or {}).get("text") or ""
        except ValueError as exc:
            raise VoiceError("VOICE_MODEL_FAILED", "火山 ASR 响应体非 JSON") from exc
        if not text.strip():
            raise VoiceError("VOICE_AUDIO_INVALID", "未识别到语音内容")
        return text


class VolcTtsClient:
    """火山 TTS 适配（开通后补全）：按 contracts/voice.json tts_format + tts_voice 调用。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def tts(self, text: str) -> bytes:
        raise NotImplementedError("火山 TTS 开通后在此接入")


def _volcano_voice_key_ready(settings: Settings) -> bool:
    """火山语音凭据就绪判定：新版控制台单 api_key，或旧版 app_id + access_token 齐备。

    未就绪时契约 enabled=true 回落 Fake 固定文本（票 58，ADR-0029），演示链路完整。
    """
    return bool(settings.volc_asr_api_key) or bool(
        settings.volc_asr_app_id and settings.volc_asr_access_token
    )


class VoiceService:
    """按 contracts/voice.json enabled + 运行时凭据选实例（与 vision interpreter 同构）。

    enabled=false 时返回 Disabled 占位（调用即抛 VOICE_UNCONFIGURED），让 server-java
    出口走降级文案；enabled=true 且凭据就绪后返回 Volc 实现，凭据未就绪回落 Fake
    （票 58，ADR-0029：Fake 返回固定识别文本，不依赖真实密钥）。测试经 inject_*
    注入 Fake 实例并钉死凭据判定，不触碰 settings。
    """

    def __init__(self) -> None:
        self._asr: AsrClient | None = None
        self._tts: TtsClient | None = None
        self._key_ready: Callable[[], bool] | None = None
        self._lazy_volc_asr: LazyDelegate[VolcAsrClient] | None = None
        self._lazy_volc_tts: LazyDelegate[VolcTtsClient] | None = None

    def inject_asr(self, client: AsrClient) -> None:
        """测试装配：注入 Fake/disabled ASR 客户端。"""
        self._asr = client

    def inject_tts(self, client: TtsClient) -> None:
        """测试装配：注入 Fake/disabled TTS 客户端。"""
        self._tts = client

    def inject_key_ready(self, fn: Callable[[], bool]) -> None:
        """测试装配：钉死凭据就绪判定，避免测试结果随本机 .env 漂移。"""
        self._key_ready = fn

    def _is_key_ready(self) -> bool:
        if self._key_ready is not None:
            return self._key_ready()
        from app.config import get_settings

        return _volcano_voice_key_ready(get_settings())

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

    def tts_client(self) -> TtsClient:
        if self._tts is not None:
            return self._tts
        contract = get_contracts().voice
        if not contract.tts_enabled:
            return _DisabledTtsClient()
        if not self._is_key_ready():
            return FakeTtsClient()
        if self._lazy_volc_tts is None:
            from app.config import get_settings

            self._lazy_volc_tts = LazyDelegate(lambda: VolcTtsClient(get_settings()))
        return self._lazy_volc_tts.get()


class LazyVoiceService:
    """首次访问时才从 settings 构建生产 VoiceService（语义见 core.lazy.LazyDelegate）。"""

    def __init__(self) -> None:
        self._delegate: VoiceService | None = None

    def _ensure(self) -> VoiceService:
        if self._delegate is None:
            self._delegate = VoiceService()
        return self._delegate

    def inject_asr(self, client: AsrClient) -> None:
        self._ensure().inject_asr(client)

    def inject_tts(self, client: TtsClient) -> None:
        self._ensure().inject_tts(client)

    def inject_key_ready(self, fn: Callable[[], bool]) -> None:
        self._ensure().inject_key_ready(fn)

    def asr_client(self) -> AsrClient:
        return self._ensure().asr_client()

    def tts_client(self) -> TtsClient:
        return self._ensure().tts_client()

"""语音双向客户端 seam（票 45，ADR-0020）：ASR 识别与 TTS 合成的可替换适配层。

与 vision interpreter 同构：定义 Protocol（AsrClient/TtsClient），骨架阶段提供 Fake
实现返回固定值，火山语音开通后补 VolcAsrClient/VolcTtsClient，按 contracts/voice.json
的 enabled + 环境密钥选实例。ASR/TTS 不在 LangGraph 循环内，不进 agent_call_logs trace；
音频全程内存流转不持久化（对齐票 12 视觉管道"原始文件处理完即清理"先例）。

未配置/超时/失败三情况一律抛 VoiceError（携带稳定错误码），调用方降级为文字，不阻塞演示。
"""

from typing import Protocol

from app.config import Settings
from app.core.contracts import get_contracts
from app.core.lazy import LazyDelegate


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
    """火山 ASR 适配（开通后补全）：按 contracts/voice.json asr_format + 环境密钥调用。

    火山 ASR 产品形态（一句话识别 vs 流式）与 endpoint 在开通后定，不进契约（server-py
    内部实现细节）；本骨架留 NotImplementedError 占位，避免未开通时误调真实服务。
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def asr(self, audio_bytes: bytes, *, audio_format: str | None) -> str:
        # 开通后：按火山 ASR SDK/HTTP 调用，超时由 contracts.asr_timeout_ms 控制
        raise NotImplementedError("火山 ASR 开通后在此接入")


class VolcTtsClient:
    """火山 TTS 适配（开通后补全）：按 contracts/voice.json tts_format + tts_voice 调用。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def tts(self, text: str) -> bytes:
        raise NotImplementedError("火山 TTS 开通后在此接入")


class VoiceService:
    """按 contracts/voice.json enabled + 运行时密钥选实例（与 vision interpreter 同构）。

    enabled=false 或密钥缺失时返回 Disabled 占位（调用即抛 VOICE_UNCONFIGURED），
    让 server-java 出口走降级文案；enabled=true 且密钥就绪后返回 Volc 实现。
    测试经 inject_* 注入 Fake 实例，不触碰 settings。
    """

    def __init__(self) -> None:
        self._asr: AsrClient | None = None
        self._tts: TtsClient | None = None
        self._lazy_volc_asr: LazyDelegate[VolcAsrClient] | None = None
        self._lazy_volc_tts: LazyDelegate[VolcTtsClient] | None = None

    def inject_asr(self, client: AsrClient) -> None:
        """测试装配：注入 Fake/disabled ASR 客户端。"""
        self._asr = client

    def inject_tts(self, client: TtsClient) -> None:
        """测试装配：注入 Fake/disabled TTS 客户端。"""
        self._tts = client

    def asr_client(self) -> AsrClient:
        if self._asr is not None:
            return self._asr
        contract = get_contracts().voice
        if not contract.asr_enabled:
            return _DisabledAsrClient()
        # 开通后：按 settings 火山密钥就绪与否选 Volc 或 Disabled（密钥检测兜底）
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

    def asr_client(self) -> AsrClient:
        return self._ensure().asr_client()

    def tts_client(self) -> TtsClient:
        return self._ensure().tts_client()

"""语音双向 HTTP seam（票 45，ADR-0020）：fake ASR/TTS 替换真实火山调用。

覆盖正常/超时/未配置/失败四类路径；断言不依赖真实密钥，骨架阶段可先行。
"""

from conftest import TEST_AGENT_SECRET, StubHealthService
from fastapi.testclient import TestClient

from app.main import create_app
from app.services.voice import VoiceError, VoiceService

_HEADERS = {"X-Agent-Callback-Token": TEST_AGENT_SECRET}
_WAV = b"RIFF....fake-audio-bytes"  # 占位音频，Fake 不解析内容


class _FakeAsr:
    def __init__(self, *, text: str | None = None, raises: VoiceError | None = None) -> None:
        self._text = text
        self._raises = raises
        self.calls: list[bytes] = []

    async def asr(self, audio_bytes: bytes, *, audio_format: str | None) -> str:
        self.calls.append(audio_bytes)
        if self._raises is not None:
            raise self._raises
        assert self._text is not None
        return self._text


class _FakeTts:
    def __init__(self, *, audio: bytes | None = None, raises: VoiceError | None = None) -> None:
        self._audio = audio
        self._raises = raises
        self.calls: list[str] = []

    async def tts(self, text: str) -> bytes:
        self.calls.append(text)
        if self._raises is not None:
            raise self._raises
        assert self._audio is not None
        return self._audio


class _TimeoutAsr:
    async def asr(self, audio_bytes: bytes, *, audio_format: str | None) -> str:
        raise TimeoutError("ASR 超时（fake）")


class _TimeoutTts:
    async def tts(self, text: str) -> bytes:
        raise TimeoutError("TTS 超时（fake）")


def _app_with(asr: object | None = None, tts: object | None = None) -> TestClient:
    service = VoiceService()
    if asr is not None:
        service.inject_asr(asr)  # type: ignore[arg-type]
    if tts is not None:
        service.inject_tts(tts)  # type: ignore[arg-type]
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        voice_service=service,
    )
    return TestClient(app)


def test_asr_returns_recognized_text() -> None:
    fake = _FakeAsr(text="我头疼两天了，该挂什么科")
    with _app_with(asr=fake) as client:
        response = client.post(
            "/api/agent/asr",
            files=[("files", ("voice.wav", _WAV, "audio/wav"))],
            headers=_HEADERS,
        )
    assert response.status_code == 200
    assert response.json()["text"] == "我头疼两天了，该挂什么科"
    assert fake.calls == [_WAV]


def test_asr_empty_audio_rejected_before_client_call() -> None:
    fake = _FakeAsr(text="不应到达")
    with _app_with(asr=fake) as client:
        response = client.post(
            "/api/agent/asr",
            files=[("files", ("voice.wav", b"", "audio/wav"))],
            headers=_HEADERS,
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VOICE_AUDIO_INVALID"
    assert fake.calls == []


def test_asr_enabled_without_keys_falls_back_to_fake() -> None:
    # 票 58（ADR-0029）：契约 asr_enabled=true 但火山语音密钥未配置 -> Fake 回落，
    # 返回固定识别文本（演示链路完整，不抛 VOICE_UNCONFIGURED）
    with _app_with() as client:
        response = client.post(
            "/api/agent/asr",
            files=[("files", ("voice.wav", _WAV, "audio/wav"))],
            headers=_HEADERS,
        )
    assert response.status_code == 200
    assert response.json()["text"] == "我头疼两天了，该挂什么科"


def test_asr_timeout_returns_stable_code() -> None:
    with _app_with(asr=_TimeoutAsr()) as client:
        response = client.post(
            "/api/agent/asr",
            files=[("files", ("voice.wav", _WAV, "audio/wav"))],
            headers=_HEADERS,
        )
    assert response.status_code == 504
    assert response.json()["detail"]["code"] == "VOICE_MODEL_TIMEOUT"


def test_asr_model_failure_returns_stable_code() -> None:
    fake = _FakeAsr(raises=VoiceError("VOICE_MODEL_FAILED", "火山 ASR 失败"))
    with _app_with(asr=fake) as client:
        response = client.post(
            "/api/agent/asr",
            files=[("files", ("voice.wav", _WAV, "audio/wav"))],
            headers=_HEADERS,
        )
    assert response.status_code == 502
    assert response.json()["detail"]["code"] == "VOICE_MODEL_FAILED"


def test_tts_returns_audio_bytes() -> None:
    fake = _FakeTts(audio=b"FAKE-TTS-AUDIO")
    with _app_with(tts=fake) as client:
        response = client.post(
            "/api/agent/tts",
            json={"text": "你好，我是小愈"},
            headers=_HEADERS,
        )
    assert response.status_code == 200
    assert response.content == b"FAKE-TTS-AUDIO"
    assert fake.calls == ["你好，我是小愈"]


def test_tts_unconfigured_returns_stable_code() -> None:
    with _app_with() as client:
        response = client.post(
            "/api/agent/tts",
            json={"text": "你好"},
            headers=_HEADERS,
        )
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "VOICE_UNCONFIGURED"


def test_tts_timeout_returns_stable_code() -> None:
    with _app_with(tts=_TimeoutTts()) as client:
        response = client.post(
            "/api/agent/tts",
            json={"text": "你好"},
            headers=_HEADERS,
        )
    assert response.status_code == 504
    assert response.json()["detail"]["code"] == "VOICE_MODEL_TIMEOUT"


def test_asr_rejects_missing_callback_token() -> None:
    fake = _FakeAsr(text="不应到达")
    with _app_with(asr=fake) as client:
        response = client.post(
            "/api/agent/asr",
            files=[("files", ("voice.wav", _WAV, "audio/wav"))],
        )
    assert response.status_code == 401
    assert fake.calls == []

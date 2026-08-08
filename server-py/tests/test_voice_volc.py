"""火山 ASR 极速版客户端单测（票 45）：httpx.MockTransport 替代真实火山调用。

断言点：新旧控制台两套鉴权头、wav base64 内联 payload、响应头 X-Api-Status-Code
状态判定（成功/静音空音频/模型失败/超时），不触网、不依赖真实凭据。
"""

import asyncio
import base64
import json

import httpx
import pytest

from app.config import Settings
from app.services.voice import (
    FakeAsrClient,
    VolcAsrClient,
    VoiceError,
    VoiceService,
    _volcano_voice_key_ready,
)

_WAV = b"RIFF....fake-audio-bytes"


def _settings(**overrides: str) -> Settings:
    # 三个凭据字段全部显式钉空，用例不随本机 .env 是否已填火山凭据而漂移
    base = {
        "agent_callback_secret": "test-only",
        "volc_asr_app_id": "app-id",
        "volc_asr_access_token": "access-token",
        "volc_asr_api_key": "",
    }
    return Settings(**(base | overrides))  # type: ignore[arg-type]


def _transport(
    handler_status: str = "20000000",
    body: dict[str, object] | None = None,
    captured: list[httpx.Request] | None = None,
) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        if captured is not None:
            captured.append(request)
        payload = body if body is not None else {"result": {"text": "我头疼两天了"}}
        return httpx.Response(
            200,
            headers={"X-Api-Status-Code": handler_status, "X-Tt-Logid": "logid-1"},
            content=json.dumps(payload).encode(),
        )

    return httpx.MockTransport(handler)


def _run(client: VolcAsrClient) -> str:
    return asyncio.run(client.asr(_WAV, audio_format="wav"))


def test_volc_asr_success_sends_old_console_headers_and_wav_base64() -> None:
    captured: list[httpx.Request] = []
    client = VolcAsrClient(_settings(), transport=_transport(captured=captured))
    assert _run(client) == "我头疼两天了"
    request = captured[0]
    assert request.headers["X-Api-App-Key"] == "app-id"
    assert request.headers["X-Api-Access-Key"] == "access-token"
    assert request.headers["X-Api-Resource-Id"] == "volc.bigasr.auc_turbo"
    payload = json.loads(request.content)
    assert payload["audio"]["format"] == "wav"
    assert base64.b64decode(payload["audio"]["data"]) == _WAV
    assert payload["request"]["model_name"] == "bigmodel"


def test_volc_asr_new_console_uses_single_api_key_header() -> None:
    captured: list[httpx.Request] = []
    settings = _settings(volc_asr_api_key="api-key")
    client = VolcAsrClient(settings, transport=_transport(captured=captured))
    assert _run(client) == "我头疼两天了"
    request = captured[0]
    assert request.headers["X-Api-Key"] == "api-key"
    assert "X-Api-App-Key" not in request.headers


def test_volc_asr_silence_maps_to_audio_invalid() -> None:
    client = VolcAsrClient(_settings(), transport=_transport(handler_status="20000003"))
    with pytest.raises(VoiceError, match="静音或空音频") as excinfo:
        _run(client)
    assert excinfo.value.code == "VOICE_AUDIO_INVALID"


def test_volc_asr_failure_status_maps_to_model_failed() -> None:
    client = VolcAsrClient(_settings(), transport=_transport(handler_status="45000001"))
    with pytest.raises(VoiceError) as excinfo:
        _run(client)
    assert excinfo.value.code == "VOICE_MODEL_FAILED"


def test_volc_asr_blank_text_maps_to_audio_invalid() -> None:
    client = VolcAsrClient(_settings(), transport=_transport(body={"result": {"text": "  "}}))
    with pytest.raises(VoiceError) as excinfo:
        _run(client)
    assert excinfo.value.code == "VOICE_AUDIO_INVALID"


def test_volc_asr_timeout_maps_to_model_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("boom", request=request)

    client = VolcAsrClient(_settings(), transport=httpx.MockTransport(handler))
    with pytest.raises(VoiceError) as excinfo:
        _run(client)
    assert excinfo.value.code == "VOICE_MODEL_TIMEOUT"


def test_key_ready_accepts_either_credential_pair() -> None:
    assert _volcano_voice_key_ready(_settings()) is True
    assert _volcano_voice_key_ready(_settings(volc_asr_api_key="k")) is True
    assert _volcano_voice_key_ready(_settings(volc_asr_app_id="", volc_asr_access_token="")) is False
    # 旧版两项必须齐备，只填其一不就绪
    assert _volcano_voice_key_ready(_settings(volc_asr_access_token="")) is False


def test_voice_service_picks_volc_asr_when_key_ready() -> None:
    service = VoiceService()
    service.inject_key_ready(lambda: True)
    assert isinstance(service.asr_client(), VolcAsrClient)


def test_voice_service_falls_back_to_fake_when_key_missing() -> None:
    service = VoiceService()
    service.inject_key_ready(lambda: False)
    assert isinstance(service.asr_client(), FakeAsrClient)

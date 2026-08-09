"""语音双向内部接口（ADR-0020）：供 server-java 转发调用的 ASR/TTS seam。

端侧不直连本接口：请求一律由 server-java 鉴权/审计后转发，识别文字/合成音频逐跳透传。
ASR/TTS 不在 LangGraph 循环内，不进 agent_call_logs trace，仅 server-java 入口审计。
"""

from typing import Annotated

from fastapi import APIRouter, File, HTTPException, Request, UploadFile
from fastapi.responses import Response

from app.api.deps import AgentCallbackAuth
from app.core.contracts import get_contracts
from app.schemas.voice import AsrResponse, TtsRequest
from app.services.voice import VoiceError

router = APIRouter(prefix="/agent", tags=["agent-voice"])


@router.post("/asr", response_model=AsrResponse)
async def asr(
    request: Request,
    files: Annotated[list[UploadFile], File()],
    _: AgentCallbackAuth,
) -> AsrResponse:
    contract = get_contracts().voice
    if not files:
        raise HTTPException(status_code=422, detail={"code": "VOICE_AUDIO_INVALID"})
    audio = await files[0].read()
    if not audio:
        raise HTTPException(status_code=422, detail={"code": "VOICE_AUDIO_INVALID"})
    try:
        text = await request.app.state.voice_service.asr_client().asr(
            audio, audio_format=contract.asr_format
        )
    except VoiceError as exc:
        raise HTTPException(status_code=_status_for(exc.code), detail={"code": exc.code}) from exc
    except TimeoutError as exc:
        raise HTTPException(status_code=504, detail={"code": "VOICE_MODEL_TIMEOUT"}) from exc
    return AsrResponse(text=text)


@router.post("/tts")
async def tts(body: TtsRequest, request: Request, _: AgentCallbackAuth) -> Response:
    contract = get_contracts().voice
    try:
        audio = await request.app.state.voice_service.tts_client().tts(body.text)
    except VoiceError as exc:
        raise HTTPException(status_code=_status_for(exc.code), detail={"code": exc.code}) from exc
    except TimeoutError as exc:
        raise HTTPException(status_code=504, detail={"code": "VOICE_MODEL_TIMEOUT"}) from exc
    # 开通后格式由 contracts/voice.json tts_format 钉死（如 audio/mpeg）；骨架阶段用占位
    media_type = contract.tts_format or "application/octet-stream"
    return Response(content=audio, media_type=media_type)


def _status_for(code: str) -> int:
    # VOICE_UNCONFIGURED -> 503（降级文案由 server-java 出口加）；其余失败 502
    if code == "VOICE_UNCONFIGURED":
        return 503
    if code == "VOICE_MODEL_TIMEOUT":
        return 504
    if code == "VOICE_AUDIO_INVALID":
        return 422
    return 502

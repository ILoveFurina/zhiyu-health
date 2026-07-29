"""Agent 对话接口（供 server-java 经 SSE 调用，ADR-0009 统一入口链路）。

端侧不直连本接口：请求一律由 server-java 鉴权/审计后转发，token 逐跳透传。
"""

import json
from collections.abc import AsyncIterator

from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse

from app.api.deps import AgentCallbackAuth
from app.schemas.chat import AgentChatRequest
from app.agent.runner import HealthProfileContext

router = APIRouter(prefix="/agent", tags=["agent"])

_SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


def _sse_frame(event: str, data: object) -> str:
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n"


@router.post("/chat")
async def chat(
    body: AgentChatRequest,
    request: Request,
    _: AgentCallbackAuth,
) -> StreamingResponse:
    chat_service = request.app.state.chat_service
    events = chat_service.stream(
        messages=[{"role": m.role, "content": m.content} for m in body.messages],
        patient_id=body.patient_id,
        conversation_id=body.conversation_id,
        health_profile=(
            HealthProfileContext(**body.health_profile.model_dump())
            if body.health_profile is not None
            else None
        ),
        effort_choice=body.effort,
        scenario=body.scenario,
        knowledge_source=body.knowledge_source,
        longitude=body.longitude,
        latitude=body.latitude,
    )

    async def frame_stream() -> AsyncIterator[str]:
        async for event in events:
            yield _sse_frame(event["event"], event["data"])  # type: ignore[arg-type]

    return StreamingResponse(frame_stream(), media_type="text/event-stream", headers=_SSE_HEADERS)

"""Agent 对话接口（供 server-java 经 SSE 调用，ADR-0009 统一入口链路）。

端侧不直连本接口：请求一律由 server-java 鉴权/审计后转发，token 逐跳透传。
"""

import json
import secrets
from collections.abc import AsyncIterator

from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import StreamingResponse

from app.schemas.chat import AgentChatRequest

router = APIRouter(prefix="/agent", tags=["agent"])

_SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


def _sse_frame(event: str, data: object) -> str:
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n"


@router.post("/chat")
async def chat(
    body: AgentChatRequest,
    request: Request,
    x_agent_callback_token: str | None = Header(default=None),
) -> StreamingResponse:
    expected = request.app.state.agent_callback_secret
    if not expected or x_agent_callback_token is None or not secrets.compare_digest(
        expected, x_agent_callback_token
    ):
        raise HTTPException(status_code=401, detail="Agent 调用认证失败")
    chat_service = request.app.state.chat_service
    events = chat_service.stream(
        messages=[{"role": m.role, "content": m.content} for m in body.messages],
        patient_id=body.patient_id,
        conversation_id=body.conversation_id,
        effort_choice=body.effort,
        scenario=body.scenario,
    )

    async def frame_stream() -> AsyncIterator[str]:
        async for event in events:
            yield _sse_frame(event["event"], event["data"])  # type: ignore[arg-type]

    return StreamingResponse(frame_stream(), media_type="text/event-stream", headers=_SSE_HEADERS)

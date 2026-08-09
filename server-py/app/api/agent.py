"""Agent 对话接口（供 server-java 经 SSE 调用，ADR-0009 统一入口链路）。

端侧不直连本接口：请求一律由 server-java 鉴权/审计后转发，token 逐跳透传。
"""

from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse

from app.api.deps import AgentCallbackAuth
from app.api.sse import SseStreamContext, log_sse_stream
from app.schemas.chat import AgentChatRequest
from app.agent.types import HealthProfileContext

router = APIRouter(prefix="/agent", tags=["agent"])

_SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


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
        retry_standard_department_id=body.retry_standard_department_id,
        preconsultation_draft_id=body.preconsultation_draft_id,
        prescription_id=body.prescription_id,
    )

    return StreamingResponse(
        # 生命周期日志集中在 SSE 出口（断流时必须能定位流走到哪、在哪断）
        log_sse_stream(
            events,
            context=SseStreamContext(
                conversation_id=body.conversation_id,
                patient_id=body.patient_id,
                effort=body.effort,
                scenario=body.scenario,
            ),
        ),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )

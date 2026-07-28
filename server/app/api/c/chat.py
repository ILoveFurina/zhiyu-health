"""C 端对话接口：SSE 推送 + 会话消息读取。"""

import json
from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse

from app.api.deps import get_current_patient_id
from app.core.constants import DISCLAIMER
from app.models import Message
from app.models.conversation import KIND_TEXT, ROLE_ASSISTANT
from app.schemas.chat import ChatRequest, MessageOut
from app.services.conversation import ConversationNotFoundError

router = APIRouter(prefix="/c", tags=["c端"])

_SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


def _sse_frame(event: str, data: object) -> str:
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n"


@router.post("/chat")
async def chat(
    body: ChatRequest,
    request: Request,
    patient_id: int = Depends(get_current_patient_id),
) -> StreamingResponse:
    chat_service = request.app.state.chat_service
    try:
        events = await chat_service.begin(
            patient_id=patient_id,
            conversation_id=body.conversation_id,
            content=body.content,
            effort_choice=body.effort,
        )
    except ConversationNotFoundError:
        raise HTTPException(status_code=404, detail="会话不存在") from None

    async def frame_stream() -> AsyncIterator[str]:
        async for event in events:
            yield _sse_frame(event["event"], event["data"])  # type: ignore[arg-type]

    return StreamingResponse(frame_stream(), media_type="text/event-stream", headers=_SSE_HEADERS)


@router.get("/conversations/{conversation_id}/messages", response_model=list[MessageOut])
def list_messages(
    conversation_id: int,
    request: Request,
    patient_id: int = Depends(get_current_patient_id),
) -> list[MessageOut]:
    conversations = request.app.state.conversation_service
    conversation = conversations.get_for_patient(conversation_id, patient_id)
    if conversation is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    return [_to_message_out(m) for m in conversations.list_messages(conversation.id)]


def _to_message_out(message: Message) -> MessageOut:
    # 免责声明注入与 SSE 通道口径一致：仅 AI 产出（assistant 文本）携带；
    # 红线警告是规则引擎产物而非 AI 产出，不注入
    is_ai_output = message.role == ROLE_ASSISTANT and message.kind == KIND_TEXT
    return MessageOut(
        id=message.id,
        role=message.role,
        kind=message.kind,
        content=message.content,
        effort=message.effort,
        disclaimer=DISCLAIMER if is_ai_output else None,
        created_at=message.created_at.isoformat(),
    )

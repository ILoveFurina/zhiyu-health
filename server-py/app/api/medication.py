"""server-java 调用的通用药品说明书流接口（票 51，ADR-0028）。

端侧不直连本接口：请求一律由 server-java 鉴权/审计后转发，token 逐跳透传。
事件序列固定为 token×N → done（事件名取自 contracts/medication-knowledge.json），
结尾 token 注入契约免责声明（硬约束 1，server-py 生成时注入）。
"""

from collections.abc import AsyncIterator

from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse

from app.api.deps import AgentCallbackAuth
from app.api.sse import sse_frame
from app.core.contracts import get_contracts
from app.schemas.medication import MedicationKnowledgeRequest

router = APIRouter(prefix="/agent/medication", tags=["agent-medication"])

_SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


async def _frames(drug_name: str, request: Request) -> AsyncIterator[str]:
    contract = get_contracts().medication_knowledge
    token_event, done_event = contract.stream_events
    streamer = request.app.state.medication_streamer
    async for token in streamer.stream(drug_name):
        yield sse_frame(token_event, {"text": token})
    # 硬约束 1：结尾注入契约免责声明（server-py 生成时注入，server-java 出口兜底）
    yield sse_frame(token_event, {"text": "\n\n" + get_contracts().disclaimer.text})
    yield sse_frame(done_event, {})


@router.post("/knowledge")
async def stream_medication_knowledge(
    body: MedicationKnowledgeRequest,
    request: Request,
    _: AgentCallbackAuth,
) -> StreamingResponse:
    return StreamingResponse(
        _frames(body.drug_name, request),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )

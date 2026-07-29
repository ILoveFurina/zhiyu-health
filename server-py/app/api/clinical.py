"""server-java 调用的处方解读与就诊小结内部接口。"""

from fastapi import APIRouter, HTTPException, Request
from openai import APITimeoutError

from app.api.deps import AgentCallbackAuth
from app.schemas.clinical import (
    ClinicalTextResponse,
    ConsultationSummaryRequest,
    PrescriptionExplanationRequest,
)

router = APIRouter(prefix="/agent/clinical", tags=["agent-clinical"])


@router.post("/prescription-explanation")
async def explain_prescription(
    body: PrescriptionExplanationRequest, request: Request, _: AgentCallbackAuth
) -> ClinicalTextResponse:
    try:
        content = await request.app.state.clinical_generator.explain_prescription(
            [item.model_dump() for item in body.items]
        )
    except (APITimeoutError, TimeoutError) as exc:
        raise HTTPException(status_code=504, detail="处方解读生成超时") from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=502, detail="处方解读暂不可用") from exc
    return ClinicalTextResponse(content=content)


@router.post("/consultation-summary")
async def summarize_consultation(
    body: ConsultationSummaryRequest, request: Request, _: AgentCallbackAuth
) -> ClinicalTextResponse:
    try:
        content = await request.app.state.clinical_generator.summarize_consultation(
            body.diagnosis, body.advice
        )
    except (APITimeoutError, TimeoutError) as exc:
        raise HTTPException(status_code=504, detail="就诊小结生成超时") from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=502, detail="就诊小结暂不可用") from exc
    return ClinicalTextResponse(content=content)

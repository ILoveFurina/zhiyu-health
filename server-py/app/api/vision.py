"""server-java 调用的内部报告解读接口。"""

from dataclasses import replace
from typing import Annotated

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from openai import APITimeoutError

from app.agent.vision.document import VisionInputError, prepare_document
from app.agent.vision.interpreter import VisionOutputError, VisionScopeError
from app.api.deps import AgentCallbackAuth
from app.core.contracts import get_contracts
from app.schemas.vision import VisionResponse
from app.schemas.chat import HealthProfilePayload

router = APIRouter(prefix="/agent/vision", tags=["agent-vision"])


def _error_detail(code: str) -> dict[str, str]:
    # 用户可见文案唯一事实源是 contracts/vision-errors.json（server-java 出口版），本端不另立
    return {"code": code, "message": get_contracts().vision_errors.messages[code]}


@router.post("/interpret")
async def interpret_report(
    request: Request,
    scenario: Annotated[str, Form()],
    files: Annotated[list[UploadFile], File()],
    _: AgentCallbackAuth,
    health_profile: Annotated[str | None, Form()] = None,
) -> VisionResponse:
    try:
        document = await prepare_document(files, scenario)
        if health_profile is not None:
            document = replace(
                document,
                health_profile=HealthProfilePayload.model_validate_json(health_profile),
            )
    except VisionInputError as exc:
        raise HTTPException(status_code=422, detail=_error_detail(exc.code)) from exc
    try:
        result = await request.app.state.vision_interpreter.interpret(document)
    except VisionScopeError as exc:
        raise HTTPException(
            status_code=422, detail=_error_detail("VISION_REPORT_SCOPE_UNSUPPORTED")
        ) from exc
    except (APITimeoutError, TimeoutError) as exc:
        raise HTTPException(
            status_code=504, detail=_error_detail("VISION_MODEL_TIMEOUT")
        ) from exc
    except VisionOutputError as exc:
        raise HTTPException(
            status_code=502, detail=_error_detail("VISION_OUTPUT_INVALID")
        ) from exc
    return VisionResponse(result=result, page_count=document.page_count)

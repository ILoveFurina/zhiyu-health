"""server-java 调用的内部报告解读接口。"""

import secrets
from typing import Annotated

from fastapi import APIRouter, File, Form, Header, HTTPException, Request, UploadFile
from openai import APITimeoutError

from app.agent.vision.document import VisionInputError, prepare_document
from app.agent.vision.interpreter import VisionOutputError, VisionScopeError
from app.schemas.vision import VisionResponse

router = APIRouter(prefix="/agent/vision", tags=["agent-vision"])


@router.post("/interpret")
async def interpret_report(
    request: Request,
    scenario: Annotated[str, Form()],
    files: Annotated[list[UploadFile], File()],
    x_agent_callback_token: Annotated[str | None, Header()] = None,
) -> VisionResponse:
    expected = request.app.state.agent_callback_secret
    if not expected or x_agent_callback_token is None or not secrets.compare_digest(
        expected, x_agent_callback_token
    ):
        raise HTTPException(status_code=401, detail="Agent 调用认证失败")
    try:
        document = await prepare_document(files, scenario)
    except VisionInputError as exc:
        raise HTTPException(
            status_code=422, detail={"code": exc.code, "message": str(exc)}
        ) from exc
    try:
        result = await request.app.state.vision_interpreter.interpret(document)
    except VisionScopeError as exc:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "VISION_REPORT_SCOPE_UNSUPPORTED",
                "message": "请上传报告文字页，暂不支持原始医学影像诊断",
            },
        ) from exc
    except (APITimeoutError, TimeoutError) as exc:
        raise HTTPException(
            status_code=504,
            detail={"code": "VISION_MODEL_TIMEOUT", "message": "报告解读模型响应超时"},
        ) from exc
    except VisionOutputError as exc:
        raise HTTPException(
            status_code=502,
            detail={"code": "VISION_OUTPUT_INVALID", "message": "报告解读结果格式无效"},
        ) from exc
    return VisionResponse(result=result, page_count=document.page_count)

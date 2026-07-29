"""无工具报告解读模型 seam 与方舟适配。"""

import base64
import json
from typing import Protocol

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import ValidationError

from app.agent.vision.document import PreparedDocument
from app.agent.vision.scenarios import policy_for
from app.config import Settings, get_settings
from app.core.lazy import LazyDelegate
from app.core.llm import build_chat_model
from app.schemas.vision import ReportInterpretation


class VisionOutputError(RuntimeError):
    pass


class VisionScopeError(RuntimeError):
    pass


class RawVisionModel(Protocol):
    async def ainvoke(self, content: list[dict[str, object]], system_prompt: str) -> str:
        """返回模型原始文本；调用方负责结构校验。"""
        ...


class VisionInterpreter(Protocol):
    async def interpret(self, document: PreparedDocument) -> ReportInterpretation:
        """将已规范化的报告页转换为严格结构化解读。"""
        ...


class StructuredVisionInterpreter:
    def __init__(self, model: RawVisionModel) -> None:
        self._model = model

    async def interpret(self, document: PreparedDocument) -> ReportInterpretation:
        content = _content_blocks(document)
        policy = policy_for(document.scenario)
        validation_hint = ""
        for attempt in range(2):
            request = list(content)
            if attempt:
                request.append(
                    {
                        "type": "text",
                        "text": "上次输出未通过结构校验："
                        + validation_hint
                        + "。请重新输出完整且严格符合 Schema 的 JSON。",
                    }
                )
            raw = await self._model.ainvoke(request, policy.system_prompt)
            try:
                result = policy.result_model.model_validate_json(raw)
                if not isinstance(result, ReportInterpretation):
                    raise VisionOutputError("模型输出了非报告解读结构")
                if not result.scope_supported:
                    raise VisionScopeError("报告范围不受支持")
                return result
            except ValidationError as exc:
                validation_hint = json.dumps(
                    exc.errors(include_input=False, include_url=False),
                    ensure_ascii=False,
                )[:2000]
            except ValueError:
                validation_hint = "返回内容不是合法 JSON"
                continue
        raise VisionOutputError("两次输出均未通过结构校验")


class ChatOpenAIVisionModel:
    def __init__(self, settings: Settings) -> None:
        model = build_chat_model(settings, reasoning_effort="high", timeout=150, max_retries=0)
        self._model = model.bind(response_format={"type": "json_object"})

    async def ainvoke(self, content: list[dict[str, object]], system_prompt: str) -> str:
        response = await self._model.ainvoke(
            [SystemMessage(content=system_prompt), HumanMessage(content=content)]  # type: ignore[arg-type]
        )
        return response.content if isinstance(response.content, str) else ""


class LazyVisionInterpreter:
    """首次调用时才从 settings 构建生产 interpreter（语义见 core.lazy.LazyDelegate）。"""

    def __init__(self) -> None:
        self._lazy: LazyDelegate[StructuredVisionInterpreter] = LazyDelegate(
            lambda: StructuredVisionInterpreter(ChatOpenAIVisionModel(get_settings()))
        )

    async def interpret(self, document: PreparedDocument) -> ReportInterpretation:
        return await self._lazy.get().interpret(document)


def _content_blocks(document: PreparedDocument) -> list[dict[str, object]]:
    blocks: list[dict[str, object]] = [
        {
            "type": "text",
            "text": "按页面顺序解读这一份报告，并输出约定 JSON。页面图像优先于机器抽取文本。",
        }
    ]
    for page in document.pages:
        blocks.append({"type": "text", "text": f"--- 第 {page.number} 页 ---"})
        if page.text:
            blocks.append({"type": "text", "text": page.text})
        if page.image and page.media_type:
            encoded = base64.b64encode(page.image).decode("ascii")
            blocks.append(
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:{page.media_type};base64,{encoded}"},
                }
            )
    return blocks

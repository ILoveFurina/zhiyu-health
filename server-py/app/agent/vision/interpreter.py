"""无工具视觉解读模型 seam 与方舟适配。

interpreter 不再写死 ReportInterpretation：按 document.scenario 取 policy，
用 policy.result_model 动态校验模型输出。scope 拒绝由场景策略驱动--若 result_model
含 scope_supported 字段且模型返回 false，则抛 VisionScopeError；不含该字段的场景
（未来可能的无 scope 概念场景）跳过此检查。这使 report 的"原始医学影像拒收"与皮肤的
"非皮肤照片拒收"复用同一断言点，新增场景无需改 interpreter。
"""

import base64
import json
from typing import Literal, Protocol

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.runnables import Runnable
from pydantic import BaseModel, ValidationError

from app.agent.vision.document import PreparedDocument
from app.agent.vision.scenarios import policy_for
from app.config import Settings, get_settings
from app.core.lazy import LazyDelegate
from app.core.llm import build_chat_model


class VisionOutputError(RuntimeError):
    pass


class VisionScopeError(RuntimeError):
    pass


class RawVisionModel(Protocol):
    async def ainvoke(
        self,
        content: list[dict[str, object]],
        system_prompt: str,
        reasoning_effort: Literal["disabled", "low", "high"],
    ) -> str:
        """返回模型原始文本；调用方负责结构校验。"""
        ...


class VisionInterpreter(Protocol):
    async def interpret(self, document: PreparedDocument) -> BaseModel:
        """将已规范化的视觉输入转换为场景对应的结构化结果。"""
        ...


class StructuredVisionInterpreter:
    def __init__(self, model: RawVisionModel) -> None:
        self._model = model

    async def interpret(self, document: PreparedDocument) -> BaseModel:
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
            raw = await self._model.ainvoke(request, policy.system_prompt, policy.reasoning_effort)
            try:
                result = policy.result_model.model_validate_json(raw)
                # scope 拒绝由场景策略驱动：result_model 含 scope_supported 时统一断言，
                # 所有场景在同一位置落实各自范围限制，拒绝结果使用场景稳定错误码。
                if _declares_scope(result) and not _scope_supported(result):
                    raise VisionScopeError("场景范围不受支持")
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
        self._settings = settings
        # 推理档位按场景策略在调用期确定（），模型按档位懒构建并缓存
        self._models: dict[str, Runnable] = {}

    def _model_for(self, reasoning_effort: Literal["disabled", "low", "high"]) -> Runnable:
        if reasoning_effort not in self._models:
            model = build_chat_model(
                self._settings, reasoning_effort=reasoning_effort, timeout=150, max_retries=0
            )
            self._models[reasoning_effort] = model.bind(response_format={"type": "json_object"})
        return self._models[reasoning_effort]

    async def ainvoke(
        self,
        content: list[dict[str, object]],
        system_prompt: str,
        reasoning_effort: Literal["disabled", "low", "high"],
    ) -> str:
        response = await self._model_for(reasoning_effort).ainvoke(
            [SystemMessage(content=system_prompt), HumanMessage(content=content)]  # type: ignore[arg-type]
        )
        return response.content if isinstance(response.content, str) else ""


class LazyVisionInterpreter:
    """首次调用时才从 settings 构建生产 interpreter（语义见 core.lazy.LazyDelegate）。"""

    def __init__(self) -> None:
        self._lazy: LazyDelegate[StructuredVisionInterpreter] = LazyDelegate(
            lambda: StructuredVisionInterpreter(ChatOpenAIVisionModel(get_settings()))
        )

    async def interpret(self, document: PreparedDocument) -> BaseModel:
        return await self._lazy.get().interpret(document)


def _declares_scope(result: BaseModel) -> bool:
    """result_model 是否声明 scope_supported 字段（排除=True 的字段不进 dump，但仍在实例上）。"""
    return "scope_supported" in type(result).model_fields


def _scope_supported(result: BaseModel) -> bool:
    # 调用方先用 _declares_scope 确认字段存在，此处直接取值。
    return bool(result.scope_supported)  # type: ignore[attr-defined]


def _content_blocks(document: PreparedDocument) -> list[dict[str, object]]:
    blocks: list[dict[str, object]] = [
        {
            "type": "text",
            "text": "按页面顺序解读这一份输入，并输出约定 JSON。页面图像优先于机器抽取文本。",
        }
    ]
    if document.health_profile is not None:
        profile = document.health_profile
        blocks.append(
            {
                "type": "text",
                "text": (
                    "当前服务对象："
                    f"{profile.display_name}，性别 {profile.gender}，出生日期 {profile.birth_date}，"
                    f"关系 {profile.relationship}，已知过敏史 "
                    f"{', '.join(profile.allergies) if profile.allergies else '未提供，无法确认'}。"
                    "解读时结合年龄、性别和过敏史，但不得擅自诊断或改变报告参考区间。"
                ),
            }
        )
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

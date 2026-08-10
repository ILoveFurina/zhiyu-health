"""基于医生结构化输入生成患者可读内容；不调用业务工具。"""

import json
from typing import Protocol

from langchain_core.messages import HumanMessage, SystemMessage

from app.config import Settings, get_settings
from app.core.lazy import LazyDelegate
from app.core.llm import build_chat_model


class ClinicalGenerator(Protocol):
    async def explain_prescription(self, items: list[dict[str, str]]) -> str: ...

    async def summarize_consultation(self, diagnosis: str, advice: str) -> str: ...


class ChatClinicalGenerator:
    def __init__(self, settings: Settings) -> None:
        # 处方解读/问诊总结是单向内容生成：disabled 关闭思考提速，质量不依赖思考档位
        self._model = build_chat_model(settings, reasoning_effort="disabled", timeout=60, max_retries=1)

    async def explain_prescription(self, items: list[dict[str, str]]) -> str:
        return await self._generate(
            "你是电子处方通俗解读助手。只能解释输入中的药品、规格、剂量、频次、疗程和备注；"
            "不得新增药品、改变医嘱或作出诊断。使用简洁中文，不要自行添加免责声明。",
            json.dumps(items, ensure_ascii=False),
        )

    async def summarize_consultation(self, diagnosis: str, advice: str) -> str:
        payload = json.dumps({"diagnosis": diagnosis, "advice": advice}, ensure_ascii=False)
        return await self._generate(
            "你是就诊小结助手。只能改写医生填写的诊断结论和医嘱，不得引用其他上下文、"
            "补充诊断、药物或治疗建议。使用简洁中文，不要自行添加免责声明。",
            payload,
        )

    async def _generate(self, system_prompt: str, payload: str) -> str:
        response = await self._model.ainvoke(
            [SystemMessage(content=system_prompt), HumanMessage(content=payload)]
        )
        content = response.content if isinstance(response.content, str) else ""
        if not content.strip():
            raise RuntimeError("模型未生成内容")
        return content.strip()


class LazyClinicalGenerator:
    def __init__(self) -> None:
        self._lazy: LazyDelegate[ChatClinicalGenerator] = LazyDelegate(
            lambda: ChatClinicalGenerator(get_settings())
        )

    async def explain_prescription(self, items: list[dict[str, str]]) -> str:
        return await self._lazy.get().explain_prescription(items)

    async def summarize_consultation(self, diagnosis: str, advice: str) -> str:
        return await self._lazy.get().summarize_consultation(diagnosis, advice)

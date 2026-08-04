"""情绪反馈判断器（票 44，ADR-0019）。

主回复 token 流完成后、message 事件发出前，发起一次非流式 LLM 调用判断用户消息
情绪（calm/anxious/fearful），response_format=json_object + pydantic 校验 + 2 次重试，
复用 agent/vision/interpreter.py 已验证的结构化输出范式。失败/超时降级 calm，
不阻塞回复（emotion 是 UI 反馈属性，非业务关键路径）。
"""

import json
from typing import Protocol

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import ValidationError

from app.config import Settings, get_settings
from app.core.lazy import LazyDelegate
from app.core.llm import build_chat_model
from app.schemas.emotion import EmotionResult


# 判断情绪用 system prompt：三档枚举 + 输出 JSON 约束，不结合健康档案、不做用药决策。
_SYSTEM_PROMPT = (
    "你是医疗 AI 助手小愈的情绪标注器。根据患者最新一条消息判断其情绪状态，"
    "只在 calm、anxious、fearful 三档中选一：\n"
    "- calm：平静，无明显焦虑或恐惧（常规咨询、健康管理等）\n"
    "- anxious：焦虑，对症状或结果感到担忧（如反复询问、表达不安）\n"
    "- fearful：恐惧，疑似面临紧急危险或极度害怕（如胸痛、呼吸困难、剧烈疼痛）\n"
    "仅输出 JSON：{\"emotion\": \"<calm|anxious|fearful>\", \"rationale\": \"<简短中文理由>\"}。"
    "不输出其他内容，不结合任何健康档案，不做诊断或用药建议。"
)


class RawEmotionModel(Protocol):
    """返回模型原始文本；调用方负责结构校验。"""

    async def ainvoke(self, user_text: str) -> str:
        ...


class EmotionJudge(Protocol):
    async def judge(self, user_text: str) -> EmotionResult:
        ...


class StructuredEmotionJudge:
    """串行二次 LLM 调用：json_object + pydantic 校验 + 2 次重试。

    超时/异常/校验失败一律降级 calm（EmotionResult.calm_default），不抛出--
    emotion 是 UI 反馈属性，失败不得阻塞主回复下发。
    """

    def __init__(self, model: RawEmotionModel) -> None:
        self._model = model

    async def judge(self, user_text: str) -> EmotionResult:
        if not user_text.strip():
            return EmotionResult.calm_default()
        validation_hint = ""
        for attempt in range(2):
            request_text = user_text if not attempt else (
                user_text + "\n\n上次输出未通过结构校验：" + validation_hint
                + "。请重新输出严格符合 Schema 的 JSON。"
            )
            try:
                raw = await self._model.ainvoke(request_text)
            except Exception:
                # 调用异常（含超时）直接降级 calm，不重试网络层（max_retries=0 已在模型层关闭）
                return EmotionResult.calm_default()
            try:
                return EmotionResult.model_validate_json(raw)
            except ValidationError as exc:
                validation_hint = json.dumps(
                    exc.errors(include_input=False, include_url=False),
                    ensure_ascii=False,
                )[:1000]
            except ValueError:
                validation_hint = "返回内容不是合法 JSON"
                continue
        return EmotionResult.calm_default()


class ChatOpenAIEmotionModel:
    """火山方舟 OpenAI 兼容接口的非流式情绪判断模型（json_object 模式）。"""

    def __init__(self, settings: Settings) -> None:
        # 情绪判断是轻量结构化任务：关闭思考、短超时、零网络重试（失败即降级 calm）。
        model = build_chat_model(settings, reasoning_effort="disabled", timeout=15, max_retries=0)
        self._model = model.bind(response_format={"type": "json_object"})

    async def ainvoke(self, user_text: str) -> str:
        response = await self._model.ainvoke(
            [SystemMessage(content=_SYSTEM_PROMPT), HumanMessage(content=user_text)]  # type: ignore[arg-type]
        )
        return response.content if isinstance(response.content, str) else ""


class LazyEmotionJudge:
    """首次调用时才从 settings 构建生产 judge（语义见 core.lazy.LazyDelegate）。

    emotion 是 UI 反馈属性而非业务关键路径：构建或调用任一环节失败均降级 calm，
    不阻塞主回复下发（ADR-0019）。这与 vision interpreter 的 fail-fast 语义不同--
    vision 失败属硬错误须阻断，emotion 失败只是回落默认白泡。
    """

    def __init__(self) -> None:
        self._lazy: LazyDelegate[StructuredEmotionJudge] = LazyDelegate(
            lambda: StructuredEmotionJudge(ChatOpenAIEmotionModel(get_settings()))
        )

    async def judge(self, user_text: str) -> EmotionResult:
        try:
            return await self._lazy.get().judge(user_text)
        except Exception:
            # 懒构建失败（如未配置方舟 key）或其它未预期异常：降级 calm 不阻塞
            return EmotionResult.calm_default()

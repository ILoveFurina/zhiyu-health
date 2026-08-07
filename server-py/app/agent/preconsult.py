"""预问诊病情摘要判定器（票 54）。

preconsultation 场景主回复 token 流完成后、message 事件发出前，编排层发起一次
非流式 LLM 调用，把本轮对话整理为结构化病情摘要（主诉/现病史/过敏史 +
受控建议标准科室）。response_format=json_object + pydantic 校验 + 2 次重试，
复用 agent/triage.py 已验证的结构化输出范式。失败/超时/校验失败一律返回 None
（降级：本轮省略快照字段，server-java 侧草稿保留上一版，对话不受影响）。
"""

import json
from typing import Any, Protocol

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import ValidationError

from app.config import Settings, get_settings
from app.core.lazy import LazyDelegate
from app.core.llm import build_chat_model
from app.schemas.preconsult import PreconsultationSummary

# 摘要整理用 system prompt：字段语义 + 候选集约束 + 输出 JSON 约束，不做诊断或用药决策。
_SYSTEM_PROMPT = (
    "你是医疗 AI 助手小愈的预问诊摘要整理器。根据对话历史与候选标准科室列表，"
    "把患者本次就诊信息整理为结构化病情摘要：\n"
    "- chief_complaint：主诉，患者最主要的症状或不适及持续时间，一句话概括\n"
    "- present_illness：现病史，症状的起病、演变、性质、伴随症状与已了解的相关情况\n"
    "- allergy_history：过敏史，已知药物或食物过敏信息\n"
    "- suggested_standard_department_id：建议标准科室，必须取自候选列表的 id，"
    "不得编造；尚不能收敛到单一科室时输出 null\n"
    "对话中尚未收集到的字段输出空字符串，不得编造患者未提供的信息；"
    "健康档案中已有的可信信息（如过敏史）直接采用，不视为编造。\n"
    "仅输出 JSON：{\"chief_complaint\": \"<主诉>\", \"present_illness\": \"<现病史>\", "
    "\"allergy_history\": \"<过敏史或空字符串>\", \"suggested_standard_department_id\": <int|null>}。"
    "不输出其他内容，不做诊断或用药建议。"
)


class RawPreconsultModel(Protocol):
    """返回模型原始文本；调用方负责结构校验。"""

    async def ainvoke(self, prompt_text: str) -> str:
        ...


class PreconsultJudge(Protocol):
    async def judge(
        self, messages: list[dict[str, str]], candidates: list[dict[str, Any]]
    ) -> PreconsultationSummary | None:
        ...


def _build_prompt(messages: list[dict[str, str]], candidates: list[dict[str, Any]]) -> str:
    history = "\n".join(
        f"{'用户' if m.get('role') == 'user' else '助手'}：{m.get('content', '')}" for m in messages
    )
    return (
        "候选标准科室："
        + json.dumps(candidates, ensure_ascii=False)
        + "\n\n对话历史：\n"
        + history
    )


def _normalize(
    summary: PreconsultationSummary, candidate_ids: set[int]
) -> PreconsultationSummary:
    """科室 ID 越界保护：目录外/臆造的 ID 一律归一化为 None。

    只矫正建议科室：摘要文本字段（含早期合法的空字符串）保留 LLM 输出原样，
    科室未收敛不构成摘要失败。空候选集（目录不可用）下任何 ID 都越界，必然 None。
    """
    if summary.suggested_standard_department_id is not None and (
        summary.suggested_standard_department_id in candidate_ids
    ):
        return summary
    return summary.model_copy(update={"suggested_standard_department_id": None})


class StructuredPreconsultJudge:
    """成功轮次后的串行二次 LLM 调用：json_object + pydantic 校验 + 2 次重试。

    超时/异常/校验失败一律返回 None，不抛出——摘要快照失败只是本轮不下发新快照，
    上一版摘要与对话历史都不受影响（与 emotion 降级 calm 的语义对称）。
    """

    def __init__(self, model: RawPreconsultModel) -> None:
        self._model = model

    async def judge(
        self, messages: list[dict[str, str]], candidates: list[dict[str, Any]]
    ) -> PreconsultationSummary | None:
        candidate_ids = {c["id"] for c in candidates if isinstance(c.get("id"), int)}
        prompt = _build_prompt(messages, candidates)
        validation_hint = ""
        for attempt in range(2):
            request_text = prompt if not attempt else (
                prompt + "\n\n上次输出未通过结构校验：" + validation_hint
                + "。请重新输出严格符合 Schema 的 JSON。"
            )
            try:
                raw = await self._model.ainvoke(request_text)
            except Exception:
                # 调用异常（含超时）直接降级 None，不重试网络层（max_retries=0 已在模型层关闭）
                return None
            try:
                return _normalize(PreconsultationSummary.model_validate_json(raw), candidate_ids)
            except ValidationError as exc:
                validation_hint = json.dumps(
                    exc.errors(include_input=False, include_url=False),
                    ensure_ascii=False,
                )[:1000]
            except ValueError:
                validation_hint = "返回内容不是合法 JSON"
                continue
        return None


class ChatOpenAIPreconsultModel:
    """火山方舟 OpenAI 兼容接口的非流式摘要整理模型（json_object 模式）。"""

    def __init__(self, settings: Settings) -> None:
        # 摘要整理是轻量结构化任务：关闭思考、短超时、零网络重试（失败即降级 None）。
        model = build_chat_model(settings, reasoning_effort="disabled", timeout=15, max_retries=0)
        self._model = model.bind(response_format={"type": "json_object"})

    async def ainvoke(self, prompt_text: str) -> str:
        response = await self._model.ainvoke(
            [SystemMessage(content=_SYSTEM_PROMPT), HumanMessage(content=prompt_text)]  # type: ignore[arg-type]
        )
        return response.content if isinstance(response.content, str) else ""


class LazyPreconsultJudge:
    """首次调用时才从 settings 构建生产 judge（语义见 core.lazy.LazyDelegate）。

    摘要快照不是业务关键路径：懒构建失败（如未配置方舟 key）或调用异常均降级
    None，本轮省略快照字段，不阻塞主回复下发（与 emotion/triage 的降级语义一致）。
    """

    def __init__(self) -> None:
        self._lazy: LazyDelegate[StructuredPreconsultJudge] = LazyDelegate(
            lambda: StructuredPreconsultJudge(ChatOpenAIPreconsultModel(get_settings()))
        )

    async def judge(
        self, messages: list[dict[str, str]], candidates: list[dict[str, Any]]
    ) -> PreconsultationSummary | None:
        try:
            return await self._lazy.get().judge(messages, candidates)
        except Exception:
            return None

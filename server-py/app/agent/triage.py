"""标准科室解析判定器（票 50）。

对话 meta 之后、Agent 流之前，编排层拉取候选标准科室并发起一次非流式 LLM
调用，判定用户意图是否已收敛到单一标准科室（explicit_booking/resolved/
ambiguous/none）。response_format=json_object + pydantic 校验 + 2 次重试，
复用 agent/emotion.py 已验证的结构化输出范式。失败/超时/越界科室 ID 一律
降级 none（不触发强制号源查询），不阻塞正常 Agent 流。
"""

import json
from typing import Any, Protocol

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import ValidationError

from app.config import Settings, get_settings
from app.core.lazy import LazyDelegate
from app.core.llm import build_chat_model
from app.schemas.triage import TriageResolution

# 科室解析用 system prompt：四态枚举 + 候选集约束 + 输出 JSON 约束，不做诊断或用药决策。
_SYSTEM_PROMPT = (
    "你是医疗 AI 助手小愈的导诊解析器。根据对话历史与候选标准科室列表，"
    "判断用户的就诊科室意图是否已收敛，只在 explicit_booking、resolved、ambiguous、none 中选一：\n"
    "- explicit_booking：用户明确表达了\"某科室 + 挂号/预约\"意图\n"
    "- resolved：多轮导诊后症状已收敛到单一明确的标准科室（用户未明说挂号）\n"
    "- ambiguous：仍有多个可能科室，无法确定单一科室\n"
    "- none：对话中没有可用的科室线索\n"
    "standard_department_id 必须取自候选列表的 id，不得编造；仅当 status 为 "
    "explicit_booking 或 resolved 时给出，其余输出 null。\n"
    "仅输出 JSON：{\"status\": \"<explicit_booking|resolved|ambiguous|none>\", "
    "\"standard_department_id\": <int|null>, \"rationale\": \"<简短中文理由>\"}。"
    "不输出其他内容，不做诊断或用药建议。"
)


class RawTriageModel(Protocol):
    """返回模型原始文本；调用方负责结构校验。"""

    async def ainvoke(self, prompt_text: str) -> str:
        ...


class TriageJudge(Protocol):
    async def judge(
        self, messages: list[dict[str, str]], candidates: list[dict[str, Any]]
    ) -> TriageResolution:
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


def _normalize(resolution: TriageResolution, candidate_ids: set[int]) -> TriageResolution:
    """科室 ID 越界保护：模型臆造的 ID 不得触发强制查询，降级 none。

    ambiguous/none 不携带科室 ID（归一化为 None），避免下游误用。
    """
    if resolution.status in ("explicit_booking", "resolved"):
        if resolution.standard_department_id in candidate_ids:
            return resolution
        return TriageResolution.none_default()
    return TriageResolution(status=resolution.status, rationale=resolution.rationale)


class StructuredTriageJudge:
    """串行前置 LLM 调用：json_object + pydantic 校验 + 2 次重试。

    超时/异常/校验失败/ID 越界一律降级 none（TriageResolution.none_default），
    不抛出——科室解析失败只是退回正常 Agent 流，不得掐断对话。
    """

    def __init__(self, model: RawTriageModel) -> None:
        self._model = model

    async def judge(
        self, messages: list[dict[str, str]], candidates: list[dict[str, Any]]
    ) -> TriageResolution:
        candidate_ids = {c["id"] for c in candidates if isinstance(c.get("id"), int)}
        if not candidate_ids:
            return TriageResolution.none_default()
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
                # 调用异常（含超时）直接降级 none，不重试网络层（max_retries=0 已在模型层关闭）
                return TriageResolution.none_default()
            try:
                return _normalize(TriageResolution.model_validate_json(raw), candidate_ids)
            except ValidationError as exc:
                validation_hint = json.dumps(
                    exc.errors(include_input=False, include_url=False),
                    ensure_ascii=False,
                )[:1000]
            except ValueError:
                validation_hint = "返回内容不是合法 JSON"
                continue
        return TriageResolution.none_default()


class ChatOpenAITriageModel:
    """火山方舟 OpenAI 兼容接口的非流式科室解析模型（json_object 模式）。"""

    def __init__(self, settings: Settings) -> None:
        # 科室解析是轻量结构化任务：关闭思考、短超时、零网络重试（失败即降级 none）。
        model = build_chat_model(settings, reasoning_effort="disabled", timeout=15, max_retries=0)
        self._model = model.bind(response_format={"type": "json_object"})

    async def ainvoke(self, prompt_text: str) -> str:
        response = await self._model.ainvoke(
            [SystemMessage(content=_SYSTEM_PROMPT), HumanMessage(content=prompt_text)]  # type: ignore[arg-type]
        )
        return response.content if isinstance(response.content, str) else ""


class LazyTriageJudge:
    """首次调用时才从 settings 构建生产 judge（语义见 core.lazy.LazyDelegate）。

    科室解析只是强制查询的前置判定：构建或调用任一环节失败均降级 none，
    退回正常 Agent 流，不阻塞对话（与 emotion 的降级语义一致）。
    """

    def __init__(self) -> None:
        self._lazy: LazyDelegate[StructuredTriageJudge] = LazyDelegate(
            lambda: StructuredTriageJudge(ChatOpenAITriageModel(get_settings()))
        )

    async def judge(
        self, messages: list[dict[str, str]], candidates: list[dict[str, Any]]
    ) -> TriageResolution:
        try:
            return await self._lazy.get().judge(messages, candidates)
        except Exception:
            # 懒构建失败（如未配置方舟 key）或其它未预期异常：降级 none 不阻塞
            return TriageResolution.none_default()

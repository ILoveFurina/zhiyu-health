"""把 LangGraph 消息投影为项目事件，并生成可安全记录的工具 trace。

本模块只解释模型/工具已经产生的消息，不执行 LangGraph，也不决定业务流程。工具失败
仍交还模型解释，只有成功的结构化结果才投影成卡片；trace 会遮蔽患者健康原文，避免
调试信息突破隐私边界。
"""

import json
from typing import Any, cast

from langchain_core.messages import AIMessage, ToolMessage

from app.agent.types import AgentOutput, CardEvent, TraceResult
from app.core.contracts import get_contracts
from app.services.reasoning import ReasoningEffort

KNOWLEDGE_TOOL = "search_knowledge"
GRAPH_TOOL = "traverse_graph"

# 查已审核处方工具名（票 80）：投影成 prescriptions 选择卡，但三态有编排层把关--
# 零张/单张抑制选择卡（文字引导或直通 prepare 确认卡），多张才投影选择卡。
PRESCRIPTIONS_TOOL = "list_approved_prescriptions"

_MASK_SENSITIVE_KEYS = frozenset(
    {
        "query",
        "chunks",
        "entities",
        "summary",
        "condition_summary",
    }
)
_MASK_PLACEHOLDER = "[已脱敏]"
_TRACE_SUMMARY_MAX_LEN = 2000


def model_outputs(message: AIMessage, effort: ReasoningEffort) -> list[AgentOutput]:
    """按模型原始顺序投影思考、工具发起与正文增量。"""
    outputs: list[AgentOutput] = []
    reasoning = _reasoning_content(message) if effort == "high" else None
    if reasoning:
        outputs.append(AgentOutput("thinking", reasoning))
    outputs.extend(_tool_start_outputs(message))
    if isinstance(message.content, str) and message.content:
        outputs.append(AgentOutput("token", message.content))
    return outputs


def tool_message_outputs(message: ToolMessage) -> list[AgentOutput]:
    """保持因果顺序：先结束 trace，再呈现工具产生的知识状态或业务卡片。"""
    outputs = [AgentOutput("tool_end", _tool_end_data(message))]
    projected = _tool_output(message)
    if projected is not None:
        outputs.append(projected)
    return outputs


def _reasoning_content(message: AIMessage) -> str | None:
    value = message.additional_kwargs.get("reasoning_content")
    return value if isinstance(value, str) and value else None


def _tool_start_outputs(message: AIMessage) -> list[AgentOutput]:
    """知识工具只用 knowledge 元事件呈现结果，不制造没有卡片对应物的 start 事件。"""
    outputs: list[AgentOutput] = []
    for call in message.tool_calls or []:
        tool_name = call.get("name")
        # OpenAI 兼容流会把参数续传成 name 为空的 tool_call chunk，它不是新的工具发起。
        if not tool_name or tool_name in (KNOWLEDGE_TOOL, GRAPH_TOOL):
            continue
        outputs.append(
            AgentOutput(
                "tool_start",
                {"tool_call_id": call.get("id"), "tool_name": tool_name},
            )
        )
    return outputs


def _parse_tool_payload(message: ToolMessage) -> dict[str, Any] | None:
    if not isinstance(message.content, str):
        return None
    try:
        payload = json.loads(message.content)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def _mask_tool_output(payload: Any) -> Any:
    """递归遮蔽健康原文；医生、科室、号源等调试所需业务结构继续保留。"""
    if isinstance(payload, dict):
        return {
            key: (_MASK_PLACEHOLDER if key in _MASK_SENSITIVE_KEYS else _mask_tool_output(value))
            for key, value in payload.items()
        }
    if isinstance(payload, list):
        return [_mask_tool_output(value) for value in payload]
    return payload


def _tool_output_summary(message: ToolMessage) -> str | None:
    payload = _parse_tool_payload(message)
    if payload is None:
        return None
    text = json.dumps(_mask_tool_output(payload), ensure_ascii=False)
    return text if len(text) <= _TRACE_SUMMARY_MAX_LEN else text[:_TRACE_SUMMARY_MAX_LEN] + "..."


def _tool_end_data(message: ToolMessage) -> dict[str, Any]:
    """生成无敏感原文的完成 trace；耗时由 server-java 按配对事件计算。"""
    data: dict[str, Any] = {
        "tool_call_id": message.tool_call_id,
        "tool_name": message.name,
        "result": _classify_tool_result(message),
    }
    summary = _tool_output_summary(message)
    if summary is not None:
        data["tool_output_summary"] = summary
    return data


def _classify_tool_result(message: ToolMessage) -> TraceResult:
    payload = _parse_tool_payload(message)
    if payload is None:
        return "error"
    if message.name in (KNOWLEDGE_TOOL, GRAPH_TOOL):
        return "success" if int(payload.get("count", 0)) > 0 else "skipped"
    return "success"


def _tool_output(message: ToolMessage) -> AgentOutput | None:
    """空召回是可继续的裸 LLM 降级；非结构化错误不投影卡片。"""
    payload = _parse_tool_payload(message)
    if payload is None:
        return None
    if message.name in (KNOWLEDGE_TOOL, GRAPH_TOOL):
        count = int(payload.get("count", 0))
        return AgentOutput(
            "knowledge",
            {
                "source": "rag" if message.name == KNOWLEDGE_TOOL else "graph",
                "status": "ok" if count > 0 else "degraded",
                "count": count,
            },
        )
    # 票 80：处方药购药三态由编排代码而非模型决定是否产选择卡
    # - 零处方：抑制选择卡，让模型按提示词文字引导「暂无已审核处方，可先发起问诊或挂号让医生开方」
    # - 单处方：抑制选择卡，让模型直接调 prepare_drug_order(prescription_id=...) 走 79 直通确认卡
    # - 多处方：投影 prescriptions 选择卡供用户点选（点选经 prescription_id 上下文注入触发 prepare）
    # 工具调用本身成功（查询有结果只是无数据/单张），_classify_tool_result 仍记 success。
    if message.name == PRESCRIPTIONS_TOOL:
        prescriptions = payload.get("prescriptions")
        count = len(prescriptions) if isinstance(prescriptions, list) else 0
        if count <= 1:
            return None
    event = _tool_event(message.name)
    return AgentOutput(event, payload) if event is not None else None


def _tool_event(tool_name: str | None) -> CardEvent | None:
    if tool_name is None:
        return None
    event = get_contracts().sse_events.tool_to_event.get(tool_name)
    return cast(CardEvent, event) if event is not None else None

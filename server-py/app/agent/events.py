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
# 零张/单张抑制选择卡（文字引导或直通 prepare 预览卡），多张才投影选择卡。
PRESCRIPTIONS_TOOL = "list_approved_prescriptions"

# 购药预览卡工具名（票 88，ADR-0035）：prepare 回调返回含价格/库存/配送费等实时测算，
# 这些只给模型叙述用；投影成卡片前必须按白名单收敛，卡片只承载非敏感稳定事实。
PREPARE_DRUG_ORDER_TOOL = "prepare_drug_order"

# 预览卡固定提示：价格与库存以统一购药确认页实时校验为准，卡片不作承诺。
_PREVIEW_PRICE_STOCK_NOTICE = "价格库存以确认页为准"
# 预览卡白名单字段（contracts/order-flow.json _drug_order_card_schema_doc）：
# 公共字段 + 处方药路径的处方来源事实；收货人/电话/地址/取药方式/物流/价格库存一律排除。
_PREVIEW_ITEM_KEYS = ("medication_id", "name", "specification", "quantity")
_PREVIEW_PRESCRIPTION_KEYS = ("doctor_name", "prescription_date", "hospital_name", "campus_name")

# RAG 检索词 query 与命中知识片段 chunks 是 LLM 据症状检索的医学知识（症状词/医学术语/
# 库内知识原文，非患者原文逐字回显），保留入 trace 供回看检索质量（硬约束 5 例外见 ADR-0017）；
# 其余健康原文载体仍遮蔽。
_MASK_SENSITIVE_KEYS = frozenset(
    {
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
        # 工具返回纯字符串是业务降级引导（如科室名匹配不上时的"可用标准科室为…"），
        # 执行本身成功，记 success；content 非 str 才是真异常（LangGraph 未配错误处理时
        # 异常会中断流而非落到此处，此分支实际由字符串降级触发）。
        return "success" if isinstance(message.content, str) else "error"
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
    # - 单处方：抑制选择卡，让模型直接调 prepare_drug_order(prescription_id=...) 直通预览卡
    # - 多处方：投影 prescriptions 选择卡供用户点选（点选经 prescription_id 上下文注入触发 prepare）
    # 工具调用本身成功（查询有结果只是无数据/单张），_classify_tool_result 仍记 success。
    if message.name == PRESCRIPTIONS_TOOL:
        prescriptions = payload.get("prescriptions")
        count = len(prescriptions) if isinstance(prescriptions, list) else 0
        if count <= 1:
            return None
    event = _tool_event(message.name)
    if event is None:
        return None
    # 票 88：购药预览卡按白名单投影——prepare/otc-prepare 回调里的实时价格、库存、
    # 配送费、院区地址等只回给模型，不落卡片；卡片另附「价格库存以确认页为准」。
    if message.name == PREPARE_DRUG_ORDER_TOOL:
        return AgentOutput(event, _drug_order_preview_card(payload))
    return AgentOutput(event, payload)


def _drug_order_preview_card(payload: dict[str, Any]) -> dict[str, Any]:
    """把 prepare/otc-prepare 回调收敛成购药预览卡 payload（白名单字段 + 固定提示）。

    跳页载荷与展示内容合一：source + prescription_id（处方药）或 items（OTC）即端侧
    跳统一购药确认页所需全部上下文，下单与实时校验都在确认页发生。
    """
    raw_items = payload.get("items")
    items = (
        [
            {key: item.get(key) for key in _PREVIEW_ITEM_KEYS}
            for item in raw_items
            if isinstance(item, dict)
        ]
        if isinstance(raw_items, list)
        else []
    )
    card: dict[str, Any] = {
        "source": payload.get("source"),
        "prescription_id": payload.get("prescription_id"),
        "items": items,
        "price_stock_notice": _PREVIEW_PRICE_STOCK_NOTICE,
    }
    if payload.get("source") == get_contracts().order_flow.sources["prescription"]:
        for key in _PREVIEW_PRESCRIPTION_KEYS:
            card[key] = payload.get(key)
        pharmacy = payload.get("pharmacy")
        card["pharmacy_name"] = pharmacy.get("display_name") if isinstance(pharmacy, dict) else None
    return card


def _tool_event(tool_name: str | None) -> CardEvent | None:
    if tool_name is None:
        return None
    event = get_contracts().sse_events.tool_to_event.get(tool_name)
    return cast(CardEvent, event) if event is not None else None

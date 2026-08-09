"""契约消费一致性：断言代码实际使用的常量与 contracts/*.json 一致（防"接了但漏接"）。

重点覆盖无法动态化的残留字面量：AgentOutput.event 的 Literal、错误码标识符等。
"""

import re
from pathlib import Path
from typing import Any, get_args

import pytest
from pydantic import ValidationError

from app.agent.runner import AgentOutput, _PRECONSULT_SCENARIO, _tool_event
from app.agent.vision import document, interpreter
from app.api import vision as vision_api
from app.core.contracts import get_contracts
from app.schemas.chat import AgentChatRequest
from app.schemas.preconsult import PreconsultationSummary
from app.services import chat as chat_service
from app.services.reasoning import _AUTO_BY_SCENARIO, Scenario


def _literal_values(annotation: Any) -> set[str]:
    values: set[str] = set()
    for arg in get_args(annotation):
        nested = get_args(arg)
        values.update(nested if nested else (arg,))
    return values


def _vision_codes_in(source: Path) -> set[str]:
    return {
        m.group(0).strip('"') for m in re.finditer(r'"VISION_[A-Z_]+"', source.read_text("utf-8"))
    }


def test_stream_event_constants_match_contract() -> None:
    events = get_contracts().sse_events.stream_events
    assert [
        chat_service.EVENT_META,
        chat_service.EVENT_KNOWLEDGE,
        chat_service.EVENT_TOKEN,
        chat_service.EVENT_MESSAGE,
        chat_service.EVENT_DONE,
    ] == events
    assert get_contracts().chat_realtime.thinking_event == chat_service.EVENT_THINKING


def test_websocket_auth_envelopes_precede_round_envelopes() -> None:
    assert get_contracts().chat_realtime.envelope_types == [
        "auth",
        "authenticated",
        "chat",
        "accepted",
        "event",
        "error",
    ]


def test_trace_events_are_disjoint_from_card_and_stream_events() -> None:
    # 票 24：trace 事件名集合必须与 card_events/ai_card_kinds/stream_events 严格不相交，
    # 且不得与 done 重名（done 是轮次终止信号，trace 不得冒充）。
    sse = get_contracts().sse_events
    assert sse.trace_events == ["tool_start", "tool_end"]
    assert sse.trace_results == ["success", "error", "skipped"]
    assert sse.trace_error_code_unknown == "TOOL_ERROR_UNKNOWN"
    others = (
        set(sse.card_events)
        | set(sse.ai_card_kinds)
        | set(sse.stream_events)
        | {sse.red_flag_event}
    )
    for trace in sse.trace_events:
        assert trace not in others, f"trace 事件 {trace} 不得与其他事件重名"


def test_agent_output_event_literal_matches_contract() -> None:
    # Literal 无法动态化，这里钉死它与契约 card_events + token + knowledge + trace_events 的一致
    # knowledge 是检索元事件（非卡片），search_knowledge 工具结果投影成它
    knowledge_event = get_contracts().knowledge.knowledge_meta_event
    event_type = AgentOutput.__dataclass_fields__["event"].type
    assert _literal_values(event_type) == {
        "token",
        get_contracts().chat_realtime.thinking_event,
        knowledge_event,
        *get_contracts().sse_events.card_events,
        *get_contracts().sse_events.trace_events,
    }


def test_tool_event_mapping_follows_contract() -> None:
    mapping = get_contracts().sse_events.tool_to_event
    for tool_name, event in mapping.items():
        assert _tool_event(tool_name) == event
    assert _tool_event("no_such_tool") is None
    assert _tool_event(None) is None
    # 票 50：find_hospitals 已移除；department_slots 由编排代码产出，不经工具投影
    assert _tool_event("find_hospitals") is None


def test_drug_order_cards_are_registered_consistently() -> None:
    # 票 78：购药确认卡（drug_order_confirm）/结果卡（drug_order）四集合一致登记，
    # 且与 trace_events 不相交、不与 done 重名；CardEvent Literal 同步覆盖。
    # drug_order 结果卡 server-py 不产出（C 端下单后 server-java 本地落库），
    # 仍登记在 CardEvent Literal 以保持事件集合完整、与契约字面量一致。
    sse = get_contracts().sse_events
    for kind in ("drug_order_confirm", "drug_order"):
        assert kind in sse.card_events
        assert kind in sse.message_kinds
        assert kind in sse.ai_card_kinds
        assert sse.event_to_kind[kind] == kind
        assert kind not in sse.trace_events
        assert kind != sse.stream_events[-1]  # done 是轮次终止信号，不得重名
    # CardEvent Literal 与 card_events 一致（test_agent_output_event_literal_matches_contract 已钉），
    # 此处显式断言两 kind 已进 Literal，避免字面量漏更
    event_type = AgentOutput.__dataclass_fields__["event"].type
    assert {"drug_order_confirm", "drug_order"}.issubset(_literal_values(event_type))


def test_guided_registration_consumption_matches_contract() -> None:
    guided = get_contracts().guided_registration
    sse = get_contracts().sse_events
    # 卡事件名与契约一致，且已进 card_events/message_kinds/ai_card_kinds/event_to_kind
    assert guided.card_event == "department_slots"
    assert guided.card_event in sse.card_events
    assert guided.card_event in sse.message_kinds
    assert guided.card_event in sse.ai_card_kinds
    assert sse.event_to_kind[guided.card_event] == guided.card_event
    assert sse.tool_to_event["get_standard_department_slots"] == guided.card_event
    # 重试字段名与契约 retry_request_field 一致
    assert guided.retry_request_field == "retry_standard_department_id"
    assert guided.retry_request_field in AgentChatRequest.model_fields
    # 卡状态与摘要模板键齐备（票 60：recommendation 为 ok 摘要的推荐理由子句）
    assert guided.card_statuses == ["ok", "failed"]
    assert set(guided.summary_templates) == {"ok", "empty", "failed", "recommendation"}
    # 票 65：ambiguous 科室选择卡事件已进 card_events/message_kinds/ai_card_kinds/event_to_kind，
    # 候选上限与点选文案模板从契约取值（工具目录校验截断、端侧镜像文案）
    assert guided.options_card_event == "department_options"
    assert guided.options_card_event in sse.card_events
    assert guided.options_card_event in sse.message_kinds
    assert guided.options_card_event in sse.ai_card_kinds
    assert sse.event_to_kind[guided.options_card_event] == guided.options_card_event
    assert sse.tool_to_event["suggest_standard_departments"] == guided.options_card_event
    assert guided.options_max_candidates == 3
    assert "{department}" in guided.options_select_user_text


def test_vision_error_codes_in_main_sources_match_contract() -> None:
    codes = _vision_codes_in(Path(document.__file__)) | _vision_codes_in(Path(vision_api.__file__))
    assert codes == set(get_contracts().vision_errors.codes)
    # 模型 seam 只抛类型化异常，不出现线上错误码（码由 api 层按契约映射）
    assert _vision_codes_in(Path(interpreter.__file__)) == set()


def test_vision_error_messages_come_from_contract() -> None:
    messages = get_contracts().vision_errors.messages
    for code, message in messages.items():
        assert vision_api._error_detail(code) == {"code": code, "message": message}
        assert str(document._input_error(code)) == message


def test_upload_limits_constants_match_contract() -> None:
    limits = get_contracts().upload_limits
    assert document.MAX_FILE_BYTES == limits.max_file_bytes
    assert document.MAX_IMAGE_TOTAL_BYTES == limits.max_total_bytes
    assert document._MIN_FILES == limits.min_files
    assert document._MAX_FILES == limits.max_files
    assert document._IMAGE_TYPES == frozenset(
        t for t in limits.allowed_types if t != "application/pdf"
    )


def test_chat_request_defaults_and_geo_bounds_follow_contract() -> None:
    defaults = get_contracts().chat_defaults
    minimal = AgentChatRequest(
        messages=[{"role": "user", "content": "你好"}], patient_id=1, conversation_id=1
    )
    assert minimal.effort == defaults.effort_default
    assert minimal.scenario == defaults.scenario_default

    base: dict[str, Any] = {
        "messages": [{"role": "user", "content": "你好"}],
        "patient_id": 1,
        "conversation_id": 1,
    }
    AgentChatRequest(**base, longitude=defaults.longitude_max, latitude=defaults.latitude_max)
    with pytest.raises(ValidationError):
        AgentChatRequest(**base, longitude=defaults.longitude_max + 1)
    with pytest.raises(ValidationError):
        AgentChatRequest(**base, latitude=defaults.latitude_min - 1)


def test_prescription_id_optional_field_in_request_schema() -> None:
    """票 80：处方选择卡点选回传的 prescription_id 是 chat 信封可选字段。

    AgentChatRequest 字段与契约 chat_realtime.chat_optional_fields 同源：字段存在且默认 None；
    契约 chat_optional_fields 必须列出 prescription_id（与 retry_standard_department_id 同构）。
    """
    assert "prescription_id" in AgentChatRequest.model_fields
    minimal = AgentChatRequest(
        messages=[{"role": "user", "content": "按此处方买药"}], patient_id=1, conversation_id=1
    )
    assert minimal.prescription_id is None
    with_rx = AgentChatRequest(
        messages=[{"role": "user", "content": "按此处方买药"}],
        patient_id=1,
        conversation_id=1,
        prescription_id=7,
    )
    assert with_rx.prescription_id == 7
    # chat_optional_fields 必须登记 prescription_id
    assert "prescription_id" in get_contracts().chat_realtime.chat_optional_fields


def test_online_consultation_consumption_matches_contract() -> None:
    """票 55：preconsultation 场景值、摘要字段与快照事件字段名全部从契约推导。"""
    online = get_contracts().online_consultation
    defaults = get_contracts().chat_defaults
    # 场景值与共享场景枚举：chat-defaults scenarios 与 Scenario Literal 双登记
    assert online.scenario == "preconsultation"
    assert online.scenario in defaults.scenarios
    assert _literal_values(Scenario) == set(defaults.scenarios)
    scenario_type = AgentChatRequest.model_fields["scenario"].annotation
    assert online.scenario in _literal_values(scenario_type)
    # 编排代码与 runner 的场景判定都从契约取值（不私写字面量）
    assert chat_service._ONLINE.scenario == online.scenario
    assert chat_service._ONLINE.summary_event_field == online.summary_event_field
    assert _PRECONSULT_SCENARIO == online.scenario
    # 摘要字段清单与快照事件字段名
    assert online.summary_fields == ["chief_complaint", "present_illness", "allergy_history"]
    assert online.summary_event_field == "preconsultation_summary"
    assert set(PreconsultationSummary.model_fields) == {
        *online.summary_fields,
        "suggested_standard_department_id",
    }
    # 知识源默认映射：预问诊走 rag
    assert get_contracts().knowledge.default_by_scenario[online.scenario] == "rag"
    # 自动档映射：预问诊为对话型场景，关闭思考（速度优先）
    assert _AUTO_BY_SCENARIO[online.scenario] == "disabled"

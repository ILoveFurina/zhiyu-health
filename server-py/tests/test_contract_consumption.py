"""契约消费一致性：断言代码实际使用的常量与 contracts/*.json 一致（防"接了但漏接"）。

重点覆盖无法动态化的残留字面量：AgentOutput.event 的 Literal、错误码标识符等。
"""

import re
from pathlib import Path
from typing import Any, get_args

import pytest
from pydantic import ValidationError

from app.agent.runner import AgentOutput, _tool_event
from app.agent.vision import document, interpreter
from app.api import vision as vision_api
from app.core.contracts import get_contracts
from app.schemas.chat import AgentChatRequest
from app.services import chat as chat_service


def _literal_values(annotation: Any) -> set[str]:
    values: set[str] = set()
    for arg in get_args(annotation):
        nested = get_args(arg)
        values.update(nested if nested else (arg,))
    return values


def _vision_codes_in(source: Path) -> set[str]:
    return {m.group(0).strip('"') for m in re.finditer(r'"VISION_[A-Z_]+"', source.read_text("utf-8"))}


def test_stream_event_constants_match_contract() -> None:
    events = get_contracts().sse_events.stream_events
    assert [
        chat_service.EVENT_META,
        chat_service.EVENT_KNOWLEDGE,
        chat_service.EVENT_TOKEN,
        chat_service.EVENT_MESSAGE,
        chat_service.EVENT_DONE,
    ] == events


def test_trace_events_are_disjoint_from_card_and_stream_events() -> None:
    # 票 24：trace 事件名集合必须与 card_events/ai_card_kinds/stream_events 严格不相交，
    # 且不得与 done 重名（done 是轮次终止信号，trace 不得冒充）。
    sse = get_contracts().sse_events
    assert sse.trace_events == ["tool_start", "tool_end"]
    assert sse.trace_results == ["success", "error", "skipped"]
    assert sse.trace_error_code_unknown == "TOOL_ERROR_UNKNOWN"
    others = set(sse.card_events) | set(sse.ai_card_kinds) | set(sse.stream_events) | {sse.red_flag_event}
    for trace in sse.trace_events:
        assert trace not in others, f"trace 事件 {trace} 不得与其他事件重名"


def test_agent_output_event_literal_matches_contract() -> None:
    # Literal 无法动态化，这里钉死它与契约 card_events + token + knowledge + trace_events 的一致
    # knowledge 是检索元事件（非卡片），search_knowledge 工具结果投影成它
    knowledge_event = get_contracts().knowledge.knowledge_meta_event
    event_type = AgentOutput.__dataclass_fields__["event"].type
    assert _literal_values(event_type) == {
        "token", knowledge_event,
        *get_contracts().sse_events.card_events,
        *get_contracts().sse_events.trace_events,
    }


def test_tool_event_mapping_follows_contract() -> None:
    mapping = get_contracts().sse_events.tool_to_event
    for tool_name, event in mapping.items():
        assert _tool_event(tool_name) == event
    assert _tool_event("no_such_tool") is None
    assert _tool_event(None) is None


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
    assert document._IMAGE_TYPES == frozenset(t for t in limits.allowed_types if t != "application/pdf")


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

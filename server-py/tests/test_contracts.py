"""跨栈契约基座测试：加载仓库根 contracts/（与 server-java 共享同一 JSON），核对关键值。"""

from pathlib import Path

import pytest

from app.core.contracts import _load, get_contracts


def test_disclaimer_matches_authoritative_text() -> None:
    assert get_contracts().disclaimer.text == "仅供参考，不替代医生诊断"


def test_sse_event_protocol_is_complete() -> None:
    events = get_contracts().sse_events
    assert events.stream_events == ["meta", "knowledge", "token", "message", "done"]
    assert events.red_flag_event == "red_flag"
    assert len(events.card_events) == 5
    assert events.tool_to_event == {
        "recommend_doctors": "doctor_recommendations",
        "get_doctor_slots": "doctor_slots",
        "find_hospitals": "hospital_recommendations",
        "create_appointment": "appointment",
        "get_appointment": "appointments",
    }
    assert len(events.message_kinds) == 9
    assert "text" in events.message_kinds
    assert "report_interpretation" in events.message_kinds
    assert len(events.ai_card_kinds) == 6
    assert len(events.event_to_kind) == 6


def test_vision_error_codes_and_messages_are_loaded() -> None:
    errors = get_contracts().vision_errors
    assert len(errors.codes) == 11
    # 错误码集合与文案表必须一一对应
    assert set(errors.messages) == set(errors.codes)
    assert errors.messages["VISION_MODEL_TIMEOUT"] == "报告解读服务响应超时"
    assert errors.messages["VISION_OUTPUT_INVALID"] == "本次未能生成可靠的结构化解读，请重试"
    assert (
        errors.messages["VISION_REPORT_SCOPE_UNSUPPORTED"]
        == "请上传报告文字页，暂不支持原始医学影像诊断"
    )
    assert errors.messages["VISION_FILE_TOO_LARGE"] == "报告文件超出处理限制，请拆分或压缩后上传"


def test_upload_limits_match_both_stacks() -> None:
    limits = get_contracts().upload_limits
    assert limits.max_file_bytes == 10 * 1024 * 1024
    assert limits.max_total_bytes == 20 * 1024 * 1024
    assert limits.min_files == 1
    assert limits.max_files == 5
    assert limits.allowed_types == ["image/jpeg", "image/png", "application/pdf"]
    assert limits.pdf_single_file is True


def test_chat_defaults_and_geo_ranges_are_loaded() -> None:
    defaults = get_contracts().chat_defaults
    assert defaults.effort_default == "auto"
    assert defaults.scenario_default == "triage"
    assert defaults.effort_choices == ["auto", "quick", "deep"]
    assert defaults.scenarios == ["triage", "interpretation"]
    assert defaults.longitude_min == -180
    assert defaults.longitude_max == 180
    assert defaults.latitude_min == -90
    assert defaults.latitude_max == 90


def test_prescription_flow_values_are_loaded() -> None:
    flow = get_contracts().prescription_flow
    assert flow.statuses == {
        "pending": "PENDING",
        "approved": "APPROVED",
        "rejected": "REJECTED",
    }
    assert flow.decisions == {"approve": "APPROVE", "reject": "REJECT"}
    assert flow.message_types["consultation_summary"] == "CONSULTATION_SUMMARY"


def test_payment_flow_values_are_loaded() -> None:
    flow = get_contracts().payment_flow
    assert flow.statuses == {"unpaid": "UNPAID", "paid": "PAID"}
    assert flow.status_labels == {"UNPAID": "待支付", "PAID": "已支付"}
    assert flow.decisions == {"pay": "PAY"}
    assert flow.messages["pay_success"] == "支付成功"


def test_contraindication_values_are_loaded() -> None:
    contract = get_contracts().contraindication
    assert contract.decisions == {
        "safe": "SAFE",
        "blocked": "BLOCKED",
        "review_required": "REVIEW_REQUIRED",
    }
    assert contract.message_types["warning"] == "contraindication_warning"
    assert "请咨询医生或药师" in contract.messages["blocked"]


def test_knowledge_contract_values_are_loaded() -> None:
    knowledge = get_contracts().knowledge
    assert knowledge.knowledge_sources == ["rag", "graph"]
    assert knowledge.none_source == "none"
    assert knowledge.default_by_scenario == {"triage": "rag", "interpretation": "none"}
    assert knowledge.knowledge_meta_event == "knowledge"
    assert knowledge.knowledge_status == ["ok", "degraded", "unavailable"]
    assert knowledge.embedding_dimension == 2048
    assert knowledge.vector_column == "vector"
    assert knowledge.search_top_k == 3
    assert knowledge.similarity_threshold == 0.3


def test_missing_contracts_dir_fails_fast(tmp_path: Path) -> None:
    with pytest.raises(RuntimeError, match="跨栈契约加载失败"):
        _load(tmp_path / "missing")

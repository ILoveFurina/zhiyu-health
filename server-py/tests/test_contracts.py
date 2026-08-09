"""跨栈契约基座测试：加载仓库根 contracts/（与 server-java 共享同一 JSON），核对关键值。"""

import json
from pathlib import Path

import pytest

from app.core.contracts import _contracts_dir, _load, get_contracts


def test_disclaimer_matches_authoritative_text() -> None:
    assert get_contracts().disclaimer.text == "仅供参考，不替代医生诊断"
    # ADR-0024（票 17）：中医专属免责与通用文案并列，舌诊卡片叠加两条
    assert get_contracts().disclaimer.tcm_text == "体质辨识仅供参考，不替代中医面诊"


def test_sse_event_protocol_is_complete() -> None:
    events = get_contracts().sse_events
    assert events.stream_events == ["meta", "knowledge", "token", "message", "done"]
    assert events.red_flag_event == "red_flag"
    assert len(events.card_events) == 12
    assert "department_slots" in events.card_events
    # 标准科室两类卡由主 Agent 的受控工具产出
    assert "department_options" in events.card_events
    assert events.tool_to_event["get_standard_department_slots"] == "department_slots"
    assert events.tool_to_event["suggest_standard_departments"] == "department_options"
    # 票 77：购药三工具（查药/已审核处方/购药确认卡）进 tool_to_event
    assert events.tool_to_event == {
        "recommend_doctors": "doctor_recommendations",
        "get_doctor_slots": "doctor_slots",
        "create_appointment": "appointment",
        "get_appointment": "appointments",
        "get_standard_department_slots": "department_slots",
        "suggest_standard_departments": "department_options",
        "search_medications": "medications",
        "list_approved_prescriptions": "prescriptions",
        "prepare_drug_order": "drug_order_prepare",
    }
    # find_hospitals 已移除
    assert "find_hospitals" not in events.tool_to_event
    assert len(events.message_kinds) == 20
    assert "text" in events.message_kinds
    assert "report_interpretation" in events.message_kinds
    assert "skin_analysis" in events.message_kinds
    assert "image" in events.message_kinds
    assert "diet_analysis" in events.message_kinds
    assert "tongue_analysis" in events.message_kinds
    # 票 51（ADR-0028）：C 端 medication_info/medication_safety 双卡片出口已删除，
    # 说明书走流式文本（kind=text），禁忌仅留 B 端开方链路
    assert "medication_info" not in events.message_kinds
    assert "medication_safety" not in events.message_kinds
    # 票 78：购药确认卡（drug_order_confirm）/结果卡（drug_order）四集合一致登记
    assert "drug_order_confirm" in events.card_events
    assert "drug_order_confirm" in events.message_kinds
    assert "drug_order" in events.card_events
    assert "drug_order" in events.message_kinds
    assert len(events.ai_card_kinds) == 16
    assert len(events.event_to_kind) == 13
    assert events.event_to_kind["drug_order_confirm"] == "drug_order_confirm"
    assert events.event_to_kind["drug_order"] == "drug_order"


def test_guided_registration_contract_is_loaded() -> None:
    # 标准科室工具、卡事件/状态、确定性摘要模板与重试字段钉死
    guided = get_contracts().guided_registration
    assert guided.card_event == "department_slots"
    assert guided.card_statuses == ["ok", "failed"]
    assert guided.retry_request_field == "retry_standard_department_id"
    assert set(guided.summary_templates) == {"ok", "empty", "failed", "recommendation"}
    assert "{department}" in guided.summary_templates["ok"]
    assert "{earliest_date}" in guided.summary_templates["ok"]
    assert "{earliest_slot}" in guided.summary_templates["ok"]
    assert "{doctor_count}" in guided.summary_templates["ok"]
    # 票 60：推荐理由子句，拼接到 ok 摘要末尾，无擅长时整句省略
    recommendation = guided.summary_templates["recommendation"]
    assert "{doctor_name}" in recommendation
    assert "{doctor_title}" in recommendation
    assert "{doctor_specialty}" in recommendation
    assert guided.time_slot_labels == {"AM": "上午", "PM": "下午"}
    # 票 65：ambiguous 科室选择卡事件、候选上限与点选直查文案模板
    assert guided.options_card_event == "department_options"
    assert guided.options_max_candidates == 3
    assert "{department}" in guided.options_select_user_text
    assert guided.retry_user_text == "重新查询号源"


def test_online_consultation_contract_is_loaded() -> None:
    # 票 55：预问诊场景值、病情摘要字段清单与摘要快照事件字段名钉死
    online = get_contracts().online_consultation
    assert online.scenario == "preconsultation"
    assert online.summary_fields == ["chief_complaint", "present_illness", "allergy_history"]
    assert online.summary_event_field == "preconsultation_summary"


def test_medication_knowledge_contract_is_loaded() -> None:
    # 票 51（ADR-0028）：C 端通用药品说明书流事件与话术钉死
    contract = get_contracts().medication_knowledge
    assert contract.stream_events == ["token", "done"]
    assert contract.messages["consult_professional"] == "具体是否适用请咨询医生或药师"
    assert "未找到该药品" in contract.messages["unknown_drug"]


def test_vision_error_codes_and_messages_are_loaded() -> None:
    errors = get_contracts().vision_errors
    assert len(errors.codes) == 16
    # 错误码集合与文案表必须一一对应
    assert set(errors.messages) == set(errors.codes)
    assert errors.messages["VISION_MODEL_TIMEOUT"] == "报告解读服务响应超时"
    assert errors.messages["VISION_OUTPUT_INVALID"] == "本次未能生成可靠的结构化解读，请重试"
    assert errors.messages["VISION_PROFILE_INVALID"] == "请求信息无法解析，请重试"
    assert (
        errors.messages["VISION_REPORT_SCOPE_UNSUPPORTED"]
        == "请上传报告文字页，暂不支持原始医学影像诊断"
    )
    assert (
        errors.messages["VISION_SKIN_SCOPE_UNSUPPORTED"]
        == "请上传清晰的皮肤照片，暂不支持医学影像或报告诊断"
    )
    assert (
        errors.messages["VISION_DIET_SCOPE_UNSUPPORTED"]
        == "请上传清晰的饮食照片，暂不支持医学影像或报告诊断"
    )
    assert (
        errors.messages["VISION_TONGUE_SCOPE_UNSUPPORTED"]
        == "请上传清晰的舌苔照片，暂不支持医学影像或报告诊断"
    )
    assert (
        errors.messages["VISION_PILL_BOX_SCOPE_UNSUPPORTED"]
        == "请上传清晰的药盒照片，暂不支持医学影像或报告诊断"
    )
    assert errors.messages["VISION_FILE_TOO_LARGE"] == "报告文件超出处理限制，请拆分或压缩后上传"


def test_upload_limits_match_both_stacks() -> None:
    limits = get_contracts().upload_limits
    assert limits.max_file_bytes == 10 * 1024 * 1024
    assert limits.max_total_bytes == 20 * 1024 * 1024
    assert limits.min_files == 1
    assert limits.max_files == 5
    assert limits.allowed_types == ["image/jpeg", "image/png", "image/webp", "application/pdf"]
    assert limits.pdf_single_file is True


def test_chat_defaults_and_geo_ranges_are_loaded() -> None:
    defaults = get_contracts().chat_defaults
    assert defaults.effort_default == "auto"
    assert defaults.scenario_default == "triage"
    assert defaults.effort_choices == ["auto", "quick", "deep"]
    # 票 55：preconsultation 场景已登记（online-consultation.json 为场景值事实源）
    assert defaults.scenarios == ["triage", "interpretation", "preconsultation"]
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
    # 票 60：审核结果站内消息类型（消息由 server-java 写入，server-py 只钉值防漂移）
    assert flow.message_types["prescription_review_result"] == "PRESCRIPTION_REVIEW_RESULT"


def test_online_consultation_follow_up_is_pinned() -> None:
    # 票 60：随访段由 server-java 消费（COMPLETED 同事务 eager 生成），server-py 不加载该字段，
    # 直接钉 JSON 原值防双栈漂移
    data = json.loads((_contracts_dir() / "online-consultation.json").read_text("utf-8"))
    follow_up = data["follow_up"]
    assert follow_up["message_type"] == "ONLINE_CONSULTATION_FOLLOW_UP"
    assert follow_up["title"] == "随访关怀"
    assert follow_up["content"]
    assert follow_up["delay_days"] == 3


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
    assert "无法完整确认" in contract.messages["safe_without_history"]


def test_knowledge_contract_values_are_loaded() -> None:
    knowledge = get_contracts().knowledge
    assert knowledge.knowledge_sources == ["rag", "graph"]
    assert knowledge.none_source == "none"
    assert knowledge.default_by_scenario == {
        "triage": "rag",
        "interpretation": "none",
        "preconsultation": "rag",  # 票 55：预问诊默认走向量检索
    }
    assert knowledge.knowledge_meta_event == "knowledge"
    assert knowledge.knowledge_status == ["ok", "degraded", "unavailable"]
    assert knowledge.embedding_dimension == 2048
    assert knowledge.vector_column == "vector"
    assert knowledge.search_top_k == 3
    assert knowledge.similarity_threshold == 0.3


def test_emotion_contract_values_are_loaded() -> None:
    emotion = get_contracts().emotion
    assert emotion.emotions == ["calm", "anxious", "fearful"]
    assert emotion.default == "calm"
    assert emotion.carried_by == "message"
    # calm 无安抚语（映射缺省），anxious/fearful 各一条
    assert set(emotion.soothing_texts) == {"anxious", "fearful"}
    assert "别太担心" in emotion.soothing_texts["anxious"]
    assert "120" in emotion.soothing_texts["fearful"]


def test_voice_contract_skeleton_is_loaded() -> None:
    # 票 45 骨架 + 票 58（ADR-0029）：asr_enabled 已点亮为 true，asr_format 钉为 wav
    # （端侧录音 wav 16k 单声道，火山极速版直收）；tts_enabled 保持 false，格式字段留 null
    voice = get_contracts().voice
    assert voice.asr_enabled is True
    assert voice.tts_enabled is False
    assert voice.asr_format == "wav"
    assert voice.tts_format is None
    assert voice.tts_voice is None
    # 超时/最大时长占位值钉死（开通后可按火山产品形态调整）
    assert voice.asr_timeout_ms == 10000
    assert voice.asr_max_duration_ms == 60000
    assert voice.tts_timeout_ms == 15000
    # 错误码集合与降级提示必须存在（开通前后均需）
    assert "VOICE_UNCONFIGURED" in voice.error_codes
    assert "VOICE_MODEL_TIMEOUT" in voice.error_codes
    assert "VOICE_MODEL_FAILED" in voice.error_codes
    assert "VOICE_AUDIO_INVALID" in voice.error_codes
    assert "语音功能暂不可用" in voice.degrade_hint


def test_health_observations_contract_is_loaded() -> None:
    # 票 61（ADR-0031）：九项白名单指标契约登记，server-py 只负责加载与 fail-fast，
    # 确定性映射全在 server-java。
    contract = get_contracts().health_observations
    assert set(contract.value_types.values()) == {"NUMERIC", "CATEGORICAL"}
    assert len(contract.metrics) == 9
    assert "HEIGHT" in contract.metrics
    assert "RH_D_BLOOD_TYPE" in contract.metrics
    assert contract.source_types["report_ai"] == "REPORT_AI"
    assert set(contract.verification_statuses.values()) >= {
        "UNVERIFIED",
        "USER_CONFIRMED",
        "REJECTED",
        "SUPERSEDED",
    }
    assert set(contract.patient_decisions.values()) == {"CONFIRM", "CORRECT", "REJECT"}
    assert "UNMAPPED" in contract.item_states.values()


def test_missing_contracts_dir_fails_fast(tmp_path: Path) -> None:
    with pytest.raises(RuntimeError, match="跨栈契约加载失败"):
        _load(tmp_path / "missing")

"""报告解读主链路与报告日期结构化输出。"""

from fastapi.testclient import TestClient

from app.agent.vision.interpreter import StructuredVisionInterpreter
from app.testing import create_test_app
from conftest import TEST_AGENT_SECRET, StubHealthService
from vision_support import FakeRawVisionModel, FakeVisionInterpreter, _mixed_pdf, _png


def test_report_image_returns_structured_card_with_disclaimer() -> None:
    fake = FakeVisionInterpreter()
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=fake,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={
                "scenario": "REPORT",
                "health_profile": """{"id":31,"display_name":"妈妈","gender":"女",
                "birth_date":"1962-05-08","relationship":"母亲","allergies":["青霉素"]}""",
            },
            files=[("files", ("report.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )

    assert response.status_code == 200
    assert response.json()["result"]["items"][0] == {
        "name": "血红蛋白",
        "value": "108",
        "reference_range": "115-150",
        "unit": "g/L",
        "priority": "yellow",
        "explanation": "低于报告参考范围。",
        "action": "建议按医嘱复查血常规。",
        "page": 1,
    }
    assert response.json()["disclaimer"] == "仅供参考，不替代医生诊断"
    prepared = fake.calls[0]
    assert prepared.scenario == "REPORT"
    assert prepared.page_count == 1
    assert prepared.health_profile.display_name == "妈妈"
    assert prepared.health_profile.allergies == ["青霉素"]


def test_report_prompt_uses_bound_health_profile_context() -> None:
    valid = """{"summary":"结合年龄关注","items":[],"actions":[],
    "unreadable":[],"scope_supported":true}"""
    model = FakeRawVisionModel([valid])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={
                "scenario": "REPORT",
                "health_profile": """{"id":31,"display_name":"妈妈","gender":"女",
                "birth_date":"1962-05-08","relationship":"母亲","allergies":["青霉素"]}""",
            },
            files=[("files", ("report.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )

    assert response.status_code == 200
    prompt_text = " ".join(str(block.get("text", "")) for block in model.calls[0])
    assert "妈妈" in prompt_text
    assert "青霉素" in prompt_text


def test_report_prompt_does_not_treat_missing_allergies_as_confirmed_none() -> None:
    valid = """{"summary":"结合年龄关注","items":[],"actions":[],
    "unreadable":[],"scope_supported":true}"""
    model = FakeRawVisionModel([valid])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={
                "scenario": "REPORT",
                "health_profile": """{"id":31,"display_name":"妈妈","gender":"女",
                "birth_date":"1962-05-08","relationship":"母亲","allergies":[]}""",
            },
            files=[("files", ("report.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )

    assert response.status_code == 200
    prompt_text = " ".join(str(block.get("text", "")) for block in model.calls[0])
    assert "未提供，无法确认" in prompt_text
    assert "已知过敏史 无" not in prompt_text


def test_mixed_pdf_is_routed_per_page_in_original_order() -> None:
    fake = FakeVisionInterpreter()
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=fake,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "REPORT"},
            files=[("files", ("mixed.pdf", _mixed_pdf(), "application/pdf"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )

    assert response.status_code == 200
    prepared = fake.calls[0]
    assert prepared.page_count == 3
    assert [(page.number, page.mode) for page in prepared.pages] == [
        (1, "text"),
        (2, "text_image"),
        (3, "image"),
    ]


def test_invalid_model_output_is_retried_once_then_validated() -> None:
    valid = """{
      "summary":"建议关注血红蛋白",
      "items":[{"name":"血红蛋白","value":"108","reference_range":"115-150",
        "unit":"g/L","priority":"yellow","explanation":"低于参考范围",
        "action":"咨询医生","page":1}],
      "actions":["咨询医生"],"unreadable":[]
      ,"scope_supported":true
    }"""
    model = FakeRawVisionModel(["不是 JSON", valid])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "REPORT"},
            files=[("files", ("report.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )

    assert response.status_code == 200
    assert response.json()["result"]["summary"] == "建议关注血红蛋白"
    assert len(model.calls) == 2
    assert "json_invalid" in str(model.calls[1][-1]["text"])
    assert all("报告解读器" in prompt for prompt in model.system_prompts)


def test_two_invalid_model_outputs_return_stable_error_without_raw_content() -> None:
    model = FakeRawVisionModel(["患者原文不应泄露", "还是非法"])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )

    with TestClient(app, raise_server_exceptions=False) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "REPORT"},
            files=[("files", ("report.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )

    assert response.status_code == 502
    # 文案以 contracts/vision-errors.json（java 出口版）为准
    assert response.json() == {
        "detail": {
            "code": "VISION_OUTPUT_INVALID",
            "message": "本次未能生成可靠的结构化解读，请重试",
        }
    }
    assert "患者原文" not in response.text
    assert len(model.calls) == 2


# ---- 报告日期抄录（票 61，ADR-0031）：LLM 只抄录清晰可见的完整日期，不做映射/猜测 ----


def _report_result_with_dates(sample_or_exam_date: str | None, report_date: str | None) -> str:
    sample = f'"{sample_or_exam_date}"' if sample_or_exam_date is not None else "null"
    report = f'"{report_date}"' if report_date is not None else "null"
    return """{
      "summary":"血常规中血红蛋白偏低，建议结合症状咨询医生。",
      "items":[{"name":"血红蛋白","value":"108","reference_range":"115-150",
        "unit":"g/L","priority":"yellow","explanation":"低于报告参考范围。",
        "action":"建议按医嘱复查血常规。","page":1}],
      "actions":["携带报告咨询医生"],"unreadable":[],
      "sample_or_exam_date":%s,"report_date":%s,
      "scope_supported":true
    }""" % (sample, report)


def _report_app(model: FakeRawVisionModel):
    return create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )


def _post_report(client: TestClient):
    return client.post(
        "/api/agent/vision/interpret",
        data={"scenario": "REPORT"},
        files=[("files", ("report.png", _png(), "image/png"))],
        headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
    )


def test_report_dates_are_passed_through_with_disclaimer() -> None:
    # 票 61：模型抄录到完整采样日期与报告日期时，API result 原样透传给 server-java；
    # 通用免责仍挂载（硬约束 1）。
    model = FakeRawVisionModel([_report_result_with_dates("2026-07-20", "2026-07-22")])
    with TestClient(_report_app(model)) as client:
        response = _post_report(client)
    assert response.status_code == 200
    body = response.json()
    assert body["result"]["sample_or_exam_date"] == "2026-07-20"
    assert body["result"]["report_date"] == "2026-07-22"
    assert body["disclaimer"] == "仅供参考，不替代医生诊断"


def test_report_only_report_date_is_passed_through() -> None:
    # 票 61：采样日期看不清/只有年月时输出 null，报告日期正常透传。
    model = FakeRawVisionModel([_report_result_with_dates(None, "2026-07-22")])
    with TestClient(_report_app(model)) as client:
        response = _post_report(client)
    assert response.status_code == 200
    result = response.json()["result"]
    assert result["sample_or_exam_date"] is None
    assert result["report_date"] == "2026-07-22"


def test_incomplete_date_is_retried_once_then_valid() -> None:
    # 票 61：不完整日期（如 2026-08）不符合 schema，走既有重试机制：
    # 第一次非法、第二次合法，最终返回合法结果。错误以 PydanticCustomError 类型
    # 进入 validation_hint，可 JSON 序列化喂回 LLM 不崩溃。
    invalid = _report_result_with_dates("2026-08", "2026-07-22")
    valid = _report_result_with_dates("2026-07-20", "2026-07-22")
    model = FakeRawVisionModel([invalid, valid])
    with TestClient(_report_app(model)) as client:
        response = _post_report(client)
    assert response.status_code == 200
    assert response.json()["result"]["sample_or_exam_date"] == "2026-07-20"
    assert len(model.calls) == 2
    assert "invalid_full_iso_date" in str(model.calls[1][-1]["text"])


def test_two_incomplete_dates_return_502() -> None:
    # 票 61：两次输出均含不完整日期 -> VisionOutputError -> 502 VISION_OUTPUT_INVALID。
    invalid = _report_result_with_dates("2026-08", None)
    model = FakeRawVisionModel([invalid, invalid])
    with TestClient(_report_app(model), raise_server_exceptions=False) as client:
        response = _post_report(client)
    assert response.status_code == 502
    assert response.json() == {
        "detail": {
            "code": "VISION_OUTPUT_INVALID",
            "message": "本次未能生成可靠的结构化解读，请重试",
        }
    }
    assert len(model.calls) == 2


def test_report_items_keep_raw_fields_without_metric_code() -> None:
    # 票 61 边界：items 只抄录原始项目名/值/单位/参考范围，不出现 metric_code
    # （确定性映射是 server-java 职责，ReportItem extra="forbid" 也会拒绝 LLM 私加）。
    model = FakeRawVisionModel([_report_result_with_dates("2026-07-20", "2026-07-22")])
    with TestClient(_report_app(model)) as client:
        response = _post_report(client)
    assert response.status_code == 200
    item = response.json()["result"]["items"][0]
    assert set(item.keys()) == {
        "name",
        "value",
        "reference_range",
        "unit",
        "priority",
        "explanation",
        "action",
        "page",
    }
    assert "metric_code" not in item
    assert item["name"] == "血红蛋白"
    assert item["value"] == "108"
    assert item["unit"] == "g/L"

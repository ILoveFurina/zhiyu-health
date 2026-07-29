"""报告解读 HTTP seam：multipart 输入，fake 多模态模型替换真实方舟调用。"""

from io import BytesIO

import pymupdf
from conftest import TEST_AGENT_SECRET, StubHealthService
from fastapi.testclient import TestClient
from PIL import Image

from app.agent.vision.interpreter import StructuredVisionInterpreter
from app.main import create_app
from app.schemas.vision import ReportInterpretation


class FakeVisionInterpreter:
    def __init__(self) -> None:
        self.calls: list[object] = []

    async def interpret(self, document: object) -> ReportInterpretation:
        self.calls.append(document)
        return ReportInterpretation.model_validate(
            {
                "summary": "血常规中血红蛋白偏低，建议结合症状咨询医生。",
                "items": [
                    {
                        "name": "血红蛋白",
                        "value": "108",
                        "reference_range": "115-150",
                        "unit": "g/L",
                        "priority": "yellow",
                        "explanation": "低于报告参考范围。",
                        "action": "建议按医嘱复查血常规。",
                        "page": 1,
                    }
                ],
                "actions": ["携带报告咨询医生"],
                "unreadable": [],
                "scope_supported": True,
            }
        )


class FakeRawVisionModel:
    def __init__(self, responses: list[str]) -> None:
        self.responses = iter(responses)
        self.calls: list[list[dict[str, object]]] = []
        self.system_prompts: list[str] = []

    async def ainvoke(self, content: list[dict[str, object]], system_prompt: str) -> str:
        self.calls.append(content)
        self.system_prompts.append(system_prompt)
        return next(self.responses)


def _png() -> bytes:
    output = BytesIO()
    Image.new("RGB", (40, 30), "white").save(output, format="PNG")
    return output.getvalue()


def _large_png() -> bytes:
    output = BytesIO()
    Image.new("RGB", (3000, 120), "white").save(output, format="PNG")
    return output.getvalue()


def _mixed_pdf() -> bytes:
    document = pymupdf.open()
    text_page = document.new_page()
    text_page.insert_text(
        (72, 72),
        "Laboratory report narrative with enough readable characters for direct extraction.",
    )

    table_page = document.new_page()
    table_page.insert_text(
        (72, 72),
        "Blood test table Hemoglobin 108 reference 115-150 and Platelets 210 reference 100-300.",
    )
    for offset in range(4):
        table_page.draw_line((72, 100 + offset * 24), (450, 100 + offset * 24))
    for offset in range(4):
        table_page.draw_line((72 + offset * 126, 100), (72 + offset * 126, 172))

    scan_page = document.new_page()
    scan_page.insert_image(scan_page.rect, stream=_png())
    content = document.tobytes()
    document.close()
    return content


def _blank_pdf() -> bytes:
    document = pymupdf.open()
    document.new_page()
    content = document.tobytes()
    document.close()
    return content


def test_report_image_returns_structured_card_with_disclaimer() -> None:
    fake = FakeVisionInterpreter()
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=fake,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "REPORT"},
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


def test_mixed_pdf_is_routed_per_page_in_original_order() -> None:
    fake = FakeVisionInterpreter()
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    assert response.json() == {
        "detail": {"code": "VISION_OUTPUT_INVALID", "message": "报告解读结果格式无效"}
    }
    assert "患者原文" not in response.text
    assert len(model.calls) == 2


def test_large_image_is_resized_and_reencoded_before_model_call() -> None:
    fake = FakeVisionInterpreter()
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=fake,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "REPORT"},
            files=[("files", ("large.png", _large_png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )

    assert response.status_code == 200
    page = fake.calls[0].pages[0]
    with Image.open(BytesIO(page.image)) as normalized:
        assert max(normalized.size) <= 2048
        assert normalized.format == "JPEG"
    assert page.media_type == "image/jpeg"


def test_blank_pdf_is_rejected_before_model_call() -> None:
    fake = FakeVisionInterpreter()
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=fake,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "REPORT"},
            files=[("files", ("blank.pdf", _blank_pdf(), "application/pdf"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_FILE_UNREADABLE"
    assert fake.calls == []


class TimeoutInterpreter:
    async def interpret(self, document: object) -> ReportInterpretation:
        raise TimeoutError


def test_out_of_scope_medical_image_is_rejected_with_stable_code() -> None:
    raw_model = FakeRawVisionModel(
        [
            """{"summary":"请上传报告文字页","items":[],
            "actions":["上传文字报告"],"unreadable":["原始医学影像"],
            "scope_supported":false}"""
        ]
    )
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(raw_model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "REPORT"},
            files=[("files", ("xray.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_REPORT_SCOPE_UNSUPPORTED"
    assert len(raw_model.calls) == 1


def test_model_timeout_returns_distinct_stable_code() -> None:
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=TimeoutInterpreter(),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "REPORT"},
            files=[("files", ("report.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 504
    assert response.json()["detail"]["code"] == "VISION_MODEL_TIMEOUT"

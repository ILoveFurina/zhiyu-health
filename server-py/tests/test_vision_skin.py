"""皮肤拍照分析场景。"""

from fastapi.testclient import TestClient

from app.agent.vision.interpreter import StructuredVisionInterpreter
from app.testing import create_test_app
from conftest import TEST_AGENT_SECRET, StubHealthService
from vision_support import FakeRawVisionModel, FakeVisionInterpreter, _mixed_pdf, _png
# ---- 皮肤场景（票 15）：首个拍照分析场景，验证 scenario 驱动泛化路径 ----


def _skin_result() -> str:
    return """{
      "skin_type":"偏干性肤质",
      "findings":[
        {"name":"轻度干燥脱屑","severity":"green","explanation":"面颊可见轻度脱屑。","care_advice":"注意保湿。"}
      ],
      "care_summary":"整体肤质尚可，建议日常保湿防晒；如出现持续红斑请面诊皮肤科。",
      "need_doctor":false,
      "scope_supported":true
    }"""


def test_skin_image_returns_structured_card_with_disclaimer() -> None:
    model = FakeRawVisionModel([_skin_result()])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "SKIN"},
            files=[("files", ("face.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    body = response.json()
    assert body["result"]["skin_type"] == "偏干性肤质"
    assert body["result"]["findings"][0]["severity"] == "green"
    assert body["result"]["need_doctor"] is False
    assert body["disclaimer"] == "仅供参考，不替代医生诊断"
    # scope_supported 是 exclude 字段，不暴露给卡片
    assert "scope_supported" not in body["result"]
    # 皮肤 prompt 走场景策略，system_prompt 必须含皮肤分析约束而非报告解读器
    assert "皮肤照片分析助手" in model.system_prompts[0]
    assert "报告解读器" not in model.system_prompts[0]


def test_skin_scenario_rejects_pdf_input() -> None:
    # 拍照分析场景无 PDF 路由：supports_pdf=False，收到 PDF 直接拒绝，不进模型。
    fake = FakeVisionInterpreter()
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=fake,
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "SKIN"},
            files=[("files", ("face.pdf", _mixed_pdf(), "application/pdf"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_FILE_COUNT_INVALID"
    assert fake.calls == []


def test_skin_out_of_scope_image_is_rejected_with_skin_code() -> None:
    # 拍到医学影像/报告文字页时 scope_supported=false -> 皮肤场景拒绝码。
    # 即使范围不支持，模型仍须返回符合 schema 的结构（非空必填字段），scope 断言才生效。
    raw_model = FakeRawVisionModel(
        [
            """{"skin_type":"非皮肤照片","findings":[],
            "care_summary":"请上传清晰皮肤照片",
            "need_doctor":false,"scope_supported":false}"""
        ]
    )
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(raw_model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "SKIN"},
            files=[("files", ("xray.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_SKIN_SCOPE_UNSUPPORTED"
    assert len(raw_model.calls) == 1


def test_unknown_scenario_is_rejected() -> None:
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=FakeVisionInterpreter(),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "UNKNOWN"},
            files=[("files", ("a.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_SCENARIO_UNSUPPORTED"



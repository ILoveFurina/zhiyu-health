"""舌象中医辨证场景。"""

from fastapi.testclient import TestClient

from app.agent.vision.interpreter import StructuredVisionInterpreter
from app.testing import create_test_app
from conftest import TEST_AGENT_SECRET, StubHealthService
from vision_support import FakeRawVisionModel, FakeVisionInterpreter, _mixed_pdf, _png
def _diet_result() -> str:
    return """{
      "meal_type":"午餐",
      "foods":[
        {"name":"米饭","estimated_amount":"约200g","risk_level":"green","explanation":"主食碳水来源。"}
      ],
      "estimated_calories":"约 450 千卡",
      "nutrition_summary":"碳水为主，蛋白质偏少，建议搭配蔬菜。",
      "diet_advice":"整体热量适中，建议增加绿叶蔬菜与优质蛋白。",
      "personal_tip":"",
      "need_doctor":false,
      "scope_supported":true
    }"""



# ---- 舌苔中医辨证场景（票 17，ADR-0024）：照搬 15/16 拍照模板，验证中医合规边界 ----


def _tongue_result() -> str:
    return """{
      "constitution":"气虚质",
      "tongue_features":"舌质淡红，舌体胖大有齿痕，舌苔薄白",
      "care_direction":"宜规律作息、适度有氧运动，饮食有节，可佐以山药、红枣等日常食材调养",
      "diet_principle":"少食生冷与油腻，饮食定时定量，宜温热熟食",
      "urgency_hint":"",
      "need_doctor":false,
      "scope_supported":true
    }"""


def test_tongue_image_returns_structured_card_with_disclaimer() -> None:
    model = FakeRawVisionModel([_tongue_result()])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "TONGUE"},
            files=[("files", ("tongue.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    body = response.json()
    assert body["result"]["constitution"] == "气虚质"
    assert "舌" in body["result"]["tongue_features"]
    assert body["result"]["need_doctor"] is False
    # 通用免责仍挂载（硬约束 1）
    assert body["disclaimer"] == "仅供参考，不替代医生诊断"
    # ADR-0024 第 2 条双栈注入：server-py 在 VisionResponse.tcm_disclaimer 注入中医专属免责
    assert body["tcm_disclaimer"] == "体质辨识仅供参考，不替代中医面诊"
    # scope_supported 是 exclude 字段，不暴露给卡片
    assert "scope_supported" not in body["result"]
    # 舌苔 prompt 走场景策略，system_prompt 必须含中医舌苔辨证约束
    prompt = model.system_prompts[0]
    assert "中医舌苔辨证" in prompt
    assert "报告解读器" not in prompt
    # ADR-0024 合规红线：prompt 必须显式禁止药材/方剂/剂量
    assert "药材" in prompt
    assert "方剂" in prompt
    assert "剂量" in prompt


def test_tongue_prompt_forbids_herbs_formulas_and_dosage() -> None:
    # ADR-0024 第 1 条边界：调理建议只讲方向，不出药材/方剂/剂量。
    # prompt 必须把禁令写明，使 LLM 即便想推荐药材也被前置约束拒绝。
    model = FakeRawVisionModel([_tongue_result()])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "TONGUE"},
            files=[("files", ("tongue.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    prompt = model.system_prompts[0]
    assert "严禁" in prompt
    assert "黄芩" in prompt or "附子" in prompt
    assert "六味地黄丸" in prompt or "桂枝汤" in prompt
    # 急症软兜底（ADR-0024 第 3 条）：不扩红线引擎，只在 prompt 引导"建议尽快就医"
    assert "镜面舌" in prompt
    assert "建议尽快就医" in prompt


def test_tongue_emergency_feature_soft_fallback_sets_need_doctor() -> None:
    # ADR-0024 第 3 条：舌象指向重病特征（如镜面舌/霉酱苔）时软兜底，
    # need_doctor=true 且 urgency_hint 必须填就医话术，不触发红线引擎。
    emergency_result = """{
      "constitution":"待辨明",
      "tongue_features":"舌面光如镜面，舌质红绛无苔",
      "care_direction":"建议尽快就医，由中医面诊确认",
      "diet_principle":"暂缓调理，先行就医",
      "urgency_hint":"建议尽快就医确认",
      "need_doctor":true,
      "scope_supported":true
    }"""
    model = FakeRawVisionModel([emergency_result])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "TONGUE"},
            files=[("files", ("tongue.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    body = response.json()
    assert body["result"]["need_doctor"] is True
    assert "就医" in body["result"]["urgency_hint"]


def test_tongue_scenario_rejects_pdf_input() -> None:
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
            data={"scenario": "TONGUE"},
            files=[("files", ("tongue.pdf", _mixed_pdf(), "application/pdf"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_FILE_COUNT_INVALID"
    assert fake.calls == []


def test_tongue_out_of_scope_image_is_rejected_with_tongue_code() -> None:
    # 拍到医学影像/报告文字页时 scope_supported=false -> 舌苔场景拒绝码。
    raw_model = FakeRawVisionModel(
        [
            """{"constitution":"非舌苔照片","tongue_features":"请上传清晰舌苔照片",
            "care_direction":"请上传清晰舌苔照片","diet_principle":"请上传清晰舌苔照片",
            "urgency_hint":"","need_doctor":false,"scope_supported":false}"""
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
            data={"scenario": "TONGUE"},
            files=[("files", ("xray.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_TONGUE_SCOPE_UNSUPPORTED"
    assert len(raw_model.calls) == 1


def test_non_tongue_scenario_has_empty_tcm_disclaimer() -> None:
    # ADR-0024 第 2 条：中医专属免责仅舌诊场景注入，其他场景 tcm_disclaimer 为空串，不泄漏。
    model = FakeRawVisionModel([_diet_result()])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "DIET"},
            files=[("files", ("meal.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    assert response.json()["tcm_disclaimer"] == ""


def test_tongue_need_doctor_without_urgency_hint_is_rejected() -> None:
    # ADR-0024 第 3 条软兜底保证：need_doctor=true 时 urgency_hint 不得为空。
    # prompt 已声明该约束，此处验证 schema 层兜底防 LLM 漏填就医话术。
    # model_validator 抛 ValidationError，interpreter 重试一次后仍失败 -> 502。
    invalid = """{
      "constitution":"待辨明","tongue_features":"舌面光如镜面",
      "care_direction":"建议就医","diet_principle":"暂缓调理",
      "urgency_hint":"","need_doctor":true,"scope_supported":true
    }"""
    model = FakeRawVisionModel([invalid, invalid])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "TONGUE"},
            files=[("files", ("tongue.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    # model_validator 拒绝 -> 两次重试均失败 -> VisionOutputError -> 502 VISION_OUTPUT_INVALID
    assert response.status_code == 502
    assert response.json()["detail"]["code"] == "VISION_OUTPUT_INVALID"



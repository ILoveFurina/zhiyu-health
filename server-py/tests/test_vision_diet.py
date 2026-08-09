"""饮食拍照分析场景。"""

from fastapi.testclient import TestClient

from app.agent.vision.interpreter import StructuredVisionInterpreter
from app.testing import create_test_app
from conftest import TEST_AGENT_SECRET, StubHealthService
from vision_support import FakeRawVisionModel, FakeVisionInterpreter, _mixed_pdf, _png
# ---- 饮食场景（票 16）：照搬 15 皮肤模板，验证 scenario 驱动泛化路径 ----


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


def test_diet_image_returns_structured_card_with_disclaimer() -> None:
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
    body = response.json()
    assert body["result"]["meal_type"] == "午餐"
    assert body["result"]["foods"][0]["risk_level"] == "green"
    assert body["result"]["need_doctor"] is False
    assert body["disclaimer"] == "仅供参考，不替代医生诊断"
    # scope_supported 是 exclude 字段，不暴露给卡片
    assert "scope_supported" not in body["result"]
    # 饮食 prompt 走场景策略，system_prompt 必须含饮食分析约束而非报告解读器
    assert "饮食照片分析助手" in model.system_prompts[0]
    assert "报告解读器" not in model.system_prompts[0]


def test_diet_prompt_injects_allergies_and_allergen_hit_marks_red() -> None:
    # 票 16 差异化：档案过敏史注入 prompt，命中过敏原时 LLM 应把 risk_level 置 red
    # 并在 personal_tip 产出风险提示。此处验证 prompt 注入（过敏原出现在上下文）
    # 与模型返回的结构化命中字段（red + 风险提示文案）。
    allergen_hit = """{
      "meal_type":"午餐",
      "foods":[
        {"name":"花生酱拌面","estimated_amount":"约200g","risk_level":"red","explanation":"含花生酱。"}
      ],
      "estimated_calories":"约 500 千卡",
      "nutrition_summary":"高热量",
      "diet_advice":"注意控制热量",
      "personal_tip":"检测到你对花生过敏，本餐含花生酱，请注意",
      "need_doctor":false,
      "scope_supported":true
    }"""
    model = FakeRawVisionModel([allergen_hit])
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={
                "scenario": "DIET",
                "health_profile": """{"id":31,"display_name":"妈妈","gender":"女",
                "birth_date":"1962-05-08","relationship":"母亲","allergies":["花生"]}""",
            },
            files=[("files", ("meal.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    # prompt 注入：过敏原出现在送入模型的上下文文本中
    prompt_text = " ".join(str(block.get("text", "")) for block in model.calls[0])
    assert "花生" in prompt_text
    assert "过敏史" in prompt_text
    body = response.json()
    # 命中过敏原：risk_level=red + personal_tip 含风险提示
    assert body["result"]["foods"][0]["risk_level"] == "red"
    assert "花生" in body["result"]["personal_tip"]
    assert "过敏" in body["result"]["personal_tip"]


def test_diet_scenario_rejects_pdf_input() -> None:
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
            data={"scenario": "DIET"},
            files=[("files", ("meal.pdf", _mixed_pdf(), "application/pdf"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_FILE_COUNT_INVALID"
    assert fake.calls == []


def test_diet_out_of_scope_image_is_rejected_with_diet_code() -> None:
    # 拍到医学影像/报告文字页时 scope_supported=false -> 饮食场景拒绝码。
    # 即使范围不支持，模型仍须返回符合 schema 的结构（非空必填字段），scope 断言才生效。
    raw_model = FakeRawVisionModel(
        [
            """{"meal_type":"非饮食照片","foods":[],
            "estimated_calories":"无法估量","nutrition_summary":"请上传清晰饮食照片",
            "diet_advice":"请上传清晰饮食照片","personal_tip":"",
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
            data={"scenario": "DIET"},
            files=[("files", ("xray.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_DIET_SCOPE_UNSUPPORTED"
    assert len(raw_model.calls) == 1

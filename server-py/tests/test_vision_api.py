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

    async def ainvoke(
        self, content: list[dict[str, object]], system_prompt: str, reasoning_effort: str
    ) -> str:
        self.calls.append(content)
        self.system_prompts.append(system_prompt)
        return next(self.responses)


def _png() -> bytes:
    output = BytesIO()
    Image.new("RGB", (40, 30), "white").save(output, format="PNG")
    return output.getvalue()


def _webp() -> bytes:
    # 支付宝小程序 my.uploadFile 默认把图片压缩转码为 webp，回归必须覆盖该格式。
    output = BytesIO()
    Image.new("RGB", (40, 30), "white").save(output, format="WEBP")
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
    app = create_app(
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
    app = create_app(
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
    # 文案以 contracts/vision-errors.json（java 出口版）为准
    assert response.json() == {
        "detail": {"code": "VISION_OUTPUT_INVALID", "message": "本次未能生成可靠的结构化解读，请重试"}
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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
    app = create_app(
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


# ---- 药盒场景（票 14，ADR-0025）：视觉只提候选药名，不做药品分析 ----


def _pill_box_result() -> str:
    return """{
      "candidates":[
        {"name":"阿莫西林胶囊"},
        {"name":"阿莫西林"}
      ],
      "unreadable_hint":"",
      "scope_supported":true
    }"""


def test_pill_box_image_returns_candidate_names_only() -> None:
    # 票 14 核心差异：vision 只提候选药名，不做药品分析。返回结构是候选药名列表，
    # 不含用法用量/适应症等药品分析字段--药品匹配与禁忌判定全在 server-java 完成。
    model = FakeRawVisionModel([_pill_box_result()])
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX"},
            files=[("files", ("box.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    body = response.json()
    assert len(body["result"]["candidates"]) == 2
    assert body["result"]["candidates"][0]["name"] == "阿莫西林胶囊"
    assert body["result"]["unreadable_hint"] == ""
    # 通用免责仍挂载（硬约束 1）
    assert body["disclaimer"] == "仅供参考，不替代医生诊断"
    # PILL_BOX 无中医专属免责（ADR-0024 第 2 条仅舌诊注入）
    assert body["tcm_disclaimer"] == ""
    # scope_supported 是 exclude 字段，不暴露给卡片
    assert "scope_supported" not in body["result"]
    # 药盒 prompt 走场景策略，system_prompt 必须含药盒识别约束而非报告解读器
    prompt = model.system_prompts[0]
    assert "药盒识别助手" in prompt
    assert "报告解读器" not in prompt
    # ADR-0025：prompt 必须约束只提名不做药品分析
    assert "用法用量" in prompt
    assert "说明书" in prompt


def test_pill_box_prompt_forbids_drug_analysis() -> None:
    # ADR-0025 核心约束：vision 只提候选药名，不得给出用法用量/适应症/注意事项等药品分析。
    # prompt 必须显式禁止药品分析，使 LLM 即便想给分析也被前置约束拒绝。
    model = FakeRawVisionModel([_pill_box_result()])
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX"},
            files=[("files", ("box.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    prompt = model.system_prompts[0]
    assert "不得给出" in prompt
    assert "适应症" in prompt


def test_pill_box_unreadable_returns_empty_candidates_with_hint() -> None:
    # 多药混拍/文字模糊时 candidates 为空，unreadable_hint 说明原因。
    # server-java 收到空 candidates 后落 text 消息提示用户重拍或使用查药品入口。
    unreadable = """{
      "candidates":[],
      "unreadable_hint":"文字模糊，无法可靠识别药名",
      "scope_supported":true
    }"""
    model = FakeRawVisionModel([unreadable])
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX"},
            files=[("files", ("blurry.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    body = response.json()
    assert body["result"]["candidates"] == []
    assert "文字模糊" in body["result"]["unreadable_hint"]


def test_pill_box_scenario_rejects_pdf_input() -> None:
    # 拍照分析场景无 PDF 路由：supports_pdf=False，收到 PDF 直接拒绝，不进模型。
    fake = FakeVisionInterpreter()
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=fake,
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX"},
            files=[("files", ("box.pdf", _mixed_pdf(), "application/pdf"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_FILE_COUNT_INVALID"
    assert fake.calls == []


def test_pill_box_out_of_scope_image_is_rejected_with_pill_box_code() -> None:
    # 拍到医学影像/报告文字页/皮肤/舌苔/饮食照片时 scope_supported=false -> 药盒场景拒绝码。
    raw_model = FakeRawVisionModel(
        [
            """{"candidates":[],"unreadable_hint":"请上传清晰的药盒照片",
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
            data={"scenario": "PILL_BOX"},
            files=[("files", ("xray.png", _png(), "image/png"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_PILL_BOX_SCOPE_UNSUPPORTED"
    assert len(raw_model.calls) == 1


def test_non_pill_box_scenario_has_empty_tcm_disclaimer() -> None:
    # ADR-0024 第 2 条：中医专属免责仅舌诊场景注入，药盒场景 tcm_disclaimer 为空串。
    model = FakeRawVisionModel([_pill_box_result()])
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX"},
            files=[("files", ("box.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    assert response.json()["tcm_disclaimer"] == ""


# ---- 可选健康档案编码（票 46）：无档案是合法业务状态，不得成为拍药盒前置条件 ----


def _pill_box_fake_app(fake: FakeVisionInterpreter):
    return create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=fake,
    )


def test_pill_box_missing_profile_field_enters_vision_with_none_profile() -> None:
    # 字段未传：契约上唯一的"无档案"表达，等价进入 vision 且 health_profile 为 None。
    fake = FakeVisionInterpreter()
    with TestClient(_pill_box_fake_app(fake)) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX"},
            files=[("files", ("box.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    assert fake.calls[0].health_profile is None


def test_pill_box_literal_null_profile_is_treated_as_missing() -> None:
    # 票 46 兼容：历史调用方曾把空档案序列化为字面 "null" 发出，等价视为字段未传，
    # 不再抛裸 500（原 bug：model_validate_json("null") 的 ValidationError 未捕获）。
    fake = FakeVisionInterpreter()
    with TestClient(_pill_box_fake_app(fake)) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX", "health_profile": "null"},
            files=[("files", ("box.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    assert fake.calls[0].health_profile is None


def test_pill_box_valid_profile_is_injected() -> None:
    # 有档案时保持现有行为：档案上下文正常注入 document。
    fake = FakeVisionInterpreter()
    with TestClient(_pill_box_fake_app(fake)) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={
                "scenario": "PILL_BOX",
                "health_profile": """{"id":31,"display_name":"妈妈","gender":"女",
                "birth_date":"1962-05-08","relationship":"母亲","allergies":["青霉素"]}""",
            },
            files=[("files", ("box.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    assert fake.calls[0].health_profile.display_name == "妈妈"
    assert fake.calls[0].health_profile.allergies == ["青霉素"]


def test_malformed_profile_json_returns_structured_422() -> None:
    # 畸形 JSON：契约化 422（VISION_PROFILE_INVALID），禁止泄漏为裸 500。
    # raise_server_exceptions 默认开启：若异常逃逸为 500，TestClient 会直接抛出使测试失败。
    fake = FakeVisionInterpreter()
    with TestClient(_pill_box_fake_app(fake)) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX", "health_profile": "{不是合法 JSON"},
            files=[("files", ("box.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_PROFILE_INVALID"
    assert fake.calls == []


def test_incomplete_profile_json_returns_structured_422() -> None:
    # 不完整档案（缺必填字段）：同样契约化 422，不进入 vision。
    fake = FakeVisionInterpreter()
    with TestClient(_pill_box_fake_app(fake)) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX", "health_profile": """{"id":31}"""},
            files=[("files", ("box.jpg", _png(), "image/jpeg"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_PROFILE_INVALID"
    assert fake.calls == []


def test_octet_stream_with_real_image_bytes_passes() -> None:
    # 回归：支付宝 my.uploadFile 不会可靠设置图片 Content-Type（常为 application/octet-stream）。
    # 客户端 content-type 不在白名单时，按字节 magic 探测命中 PNG/JPEG 即放行，不再误拒 422。
    model = FakeRawVisionModel([_pill_box_result()])
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX"},
            files=[("files", ("box.png", _png(), "application/octet-stream"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    assert len(model.calls) == 1


def test_non_image_bytes_with_octet_stream_rejected_as_type_invalid() -> None:
    # 非图片字节 + 非 image content-type：双判定均不命中，按 VISION_FILE_TYPE_INVALID 拒收。
    fake = FakeVisionInterpreter()
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=fake,
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX"},
            files=[("files", ("box.dat", b"plain text not an image", "application/octet-stream"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VISION_FILE_TYPE_INVALID"
    assert fake.calls == []


def test_webp_image_passes_type_check() -> None:
    # 支付宝小程序 my.uploadFile 默认压缩转码为 image/webp：白名单与字节探测都必须放行 webp。
    model = FakeRawVisionModel([_pill_box_result()])
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        vision_interpreter=StructuredVisionInterpreter(model),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/vision/interpret",
            data={"scenario": "PILL_BOX"},
            files=[("files", ("box.webp", _webp(), "image/webp"))],
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    assert len(model.calls) == 1

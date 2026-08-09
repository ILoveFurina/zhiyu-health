"""药盒候选药名识别场景。"""

from fastapi.testclient import TestClient

from app.agent.vision.interpreter import StructuredVisionInterpreter
from app.testing import create_test_app
from conftest import TEST_AGENT_SECRET, StubHealthService
from vision_support import FakeRawVisionModel, FakeVisionInterpreter, _mixed_pdf, _png
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
    app = create_test_app(
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
    app = create_test_app(
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
    app = create_test_app(
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
    app = create_test_app(
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
    app = create_test_app(
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
    app = create_test_app(
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

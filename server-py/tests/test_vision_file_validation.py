"""视觉文件、范围、档案编码与稳定错误语义。"""

from io import BytesIO

from fastapi.testclient import TestClient
from PIL import Image

from app.agent.vision.interpreter import StructuredVisionInterpreter
from app.schemas.vision import ReportInterpretation
from app.testing import create_test_app
from conftest import TEST_AGENT_SECRET, StubHealthService
from vision_support import (
    FakeRawVisionModel,
    FakeVisionInterpreter,
    _blank_pdf,
    _large_png,
    _png,
    _webp,
)


def _pill_box_result() -> str:
    return """{
      "candidates":[
        {"name":"阿莫西林胶囊"},
        {"name":"阿莫西林"}
      ],
      "unreadable_hint":"",
      "scope_supported":true
    }"""


def test_large_image_is_resized_and_reencoded_before_model_call() -> None:
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
    app = create_test_app(
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
    app = create_test_app(
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
    app = create_test_app(
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


# ---- 可选健康档案编码（票 46）：无档案是合法业务状态，不得成为拍药盒前置条件 ----


def _pill_box_fake_app(fake: FakeVisionInterpreter):
    return create_test_app(
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
    app = create_test_app(
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
    app = create_test_app(
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
    app = create_test_app(
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

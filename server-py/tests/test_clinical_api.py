"""处方解读与就诊小结 HTTP seam；fake 替换真实方舟模型。"""

from conftest import TEST_AGENT_SECRET, StubHealthService
from fastapi.testclient import TestClient

from app.testing import create_test_app


class FakeClinicalGenerator:
    def __init__(self) -> None:
        self.prescription_calls: list[list[dict[str, str]]] = []
        self.summary_calls: list[tuple[str, str]] = []

    async def explain_prescription(self, items: list[dict[str, str]]) -> str:
        self.prescription_calls.append(items)
        return "阿莫西林用于抗感染，请按医生给出的频次服用。"

    async def summarize_consultation(self, diagnosis: str, advice: str) -> str:
        self.summary_calls.append((diagnosis, advice))
        return "本次诊断为上呼吸道感染，请清淡饮食并按需复诊。"


def test_prescription_explanation_uses_structured_medication_facts() -> None:
    fake = FakeClinicalGenerator()
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        clinical_generator=fake,
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/clinical/prescription-explanation",
            json={
                "items": [
                    {
                        "name": "阿莫西林胶囊",
                        "specification": "0.25g*24粒",
                        "dosage": "0.5g",
                        "frequency": "每日3次",
                        "duration": "5天",
                        "notes": "饭后服用",
                    }
                ]
            },
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )

    assert response.status_code == 200
    assert response.json() == {
        "content": "阿莫西林用于抗感染，请按医生给出的频次服用。",
        "disclaimer": "仅供参考，不替代医生诊断",
    }
    assert fake.prescription_calls[0][0]["dosage"] == "0.5g"


def test_consultation_summary_receives_only_doctor_diagnosis_and_advice() -> None:
    fake = FakeClinicalGenerator()
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        clinical_generator=fake,
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/clinical/consultation-summary",
            json={"diagnosis": "上呼吸道感染", "advice": "清淡饮食，按需复诊"},
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )

    assert response.status_code == 200
    assert response.json()["content"].startswith("本次诊断")
    assert response.json()["disclaimer"] == "仅供参考，不替代医生诊断"
    assert fake.summary_calls == [("上呼吸道感染", "清淡饮食，按需复诊")]


def test_clinical_generation_requires_server_java_callback_token() -> None:
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        clinical_generator=FakeClinicalGenerator(),
    )
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/clinical/consultation-summary",
            json={"diagnosis": "上呼吸道感染", "advice": "休息"},
        )
    assert response.status_code == 401

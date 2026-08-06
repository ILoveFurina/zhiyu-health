"""通用药品说明书流 HTTP seam（票 51，ADR-0028）；fake 替换真实方舟模型。"""

import json
from collections.abc import AsyncIterator

from conftest import TEST_AGENT_SECRET, StubHealthService
from fastapi.testclient import TestClient

from app.agent.medication import build_medication_system_prompt
from app.core.contracts import get_contracts
from app.main import create_app


class FakeMedicationStreamer:
    def __init__(self, tokens: list[str]) -> None:
        self.tokens = tokens
        self.calls: list[str] = []

    def stream(self, drug_name: str) -> AsyncIterator[str]:
        self.calls.append(drug_name)

        async def _gen() -> AsyncIterator[str]:
            for token in self.tokens:
                yield token

        return _gen()


def _post(client: TestClient, drug_name: object = "阿莫西林胶囊") -> object:
    return client.post(
        "/api/agent/medication/knowledge",
        json={"drug_name": drug_name},
        headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
    )


def _parse_sse(body: str) -> list[tuple[str, dict]]:
    frames = []
    for block in body.strip().split("\n\n"):
        event = data = None
        for line in block.splitlines():
            if line.startswith("event: "):
                event = line[len("event: ") :]
            elif line.startswith("data: "):
                data = json.loads(line[len("data: ") :])
        frames.append((event, data))
    return frames


def test_knowledge_streams_tokens_then_done_with_disclaimer() -> None:
    fake = FakeMedicationStreamer(["【用途】", "用于敏感菌所致感染。", "【常见注意事项】", "遵医嘱使用。"])
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        medication_streamer=fake,
    )
    with TestClient(app) as client:
        response = _post(client)

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    frames = _parse_sse(response.text)
    # token × N（含结尾免责声明注入）→ done，事件名取自契约
    contract = get_contracts().medication_knowledge
    events = [event for event, _ in frames]
    assert events == [contract.stream_events[0]] * 5 + [contract.stream_events[1]]
    assert frames[0][1] == {"text": "【用途】"}
    assert frames[1][1] == {"text": "用于敏感菌所致感染。"}
    # 硬约束 1：结尾注入契约免责声明（server-py 生成时注入）
    assert frames[4][1]["text"].endswith(get_contracts().disclaimer.text)
    assert frames[5][1] == {}
    assert fake.calls == ["阿莫西林胶囊"]


def test_unknown_drug_wording_passes_through() -> None:
    # 不认识的药：模型按 prompt 约束输出契约 unknown_drug 话术，原样透传
    wording = get_contracts().medication_knowledge.messages["unknown_drug"]
    fake = FakeMedicationStreamer([wording])
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        medication_streamer=fake,
    )
    with TestClient(app) as client:
        response = _post(client, "不存在的药名")

    frames = _parse_sse(response.text)
    assert frames[0][1] == {"text": wording}


def test_system_prompt_enforces_generic_knowledge_boundaries() -> None:
    # prompt 边界：四节结构 + 不含患者档案 + 禁止个性化剂量与替代药 + 契约话术
    prompt = build_medication_system_prompt()
    assert "用途" in prompt
    assert "常规用法用量" in prompt
    assert "常见不良反应" in prompt
    assert "常见注意事项" in prompt
    assert "不得" in prompt and "个性化" in prompt
    assert "替代药" in prompt
    contract = get_contracts().medication_knowledge
    assert contract.messages["unknown_drug"] in prompt
    # 免责声明由代码注入，prompt 不要求模型自行添加
    assert get_contracts().disclaimer.text not in prompt


def test_blank_drug_name_is_422() -> None:
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        medication_streamer=FakeMedicationStreamer(["x"]),
    )
    with TestClient(app) as client:
        assert _post(client, "").status_code == 422
        assert _post(client, "   ").status_code == 422
        response = client.post(
            "/api/agent/medication/knowledge",
            json={},
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
        assert response.status_code == 422


def test_knowledge_requires_server_java_callback_token() -> None:
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        medication_streamer=FakeMedicationStreamer(["x"]),
    )
    with TestClient(app) as client:
        response = client.post("/api/agent/medication/knowledge", json={"drug_name": "布洛芬"})
    assert response.status_code == 401

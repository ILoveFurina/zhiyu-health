"""Agent 对话 SSE（HTTP seam，fake Agent 替换 LLM）。

覆盖：SSE 事件序列、免责声明注入、消息历史透传、推理档位映射（auto 不外传）。
"""

import asyncio
import json
from types import SimpleNamespace

import httpx
from conftest import TEST_AGENT_SECRET, FakeEmotionJudge, StubHealthService
from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, ToolCall

from app.agent.runner import LangGraphAgentRunner
from app.testing import create_test_app
from app.tools.business import build_business_tools
from app.tools.callback import BusinessCallbackClient


def _post_chat(client, payload: dict) -> list[dict]:
    # 默认关闭知识增强：既有用例聚焦业务工具卡片流，knowledge 路径由 test_knowledge 覆盖
    payload = {"knowledge_source": "none", "patient_id": 12, "conversation_id": 7, **payload}
    with client.stream(
        "POST",
        "/api/agent/chat",
        json=payload,
        headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
    ) as response:
        assert response.status_code == 200
        raw = "".join(response.iter_text())
    events = []
    for frame in raw.split("\n\n"):
        if not frame.strip():
            continue
        lines = frame.strip().split("\n")
        event = lines[0].removeprefix("event: ")
        data = json.loads(lines[1].removeprefix("data: "))
        events.append({"event": event, "data": data})
    return events


def _build_app(agent_runner) -> tuple[TestClient, FakeEmotionJudge]:
    """装配测试 app 并注入 fake emotion judge，避免命中真实方舟调用。"""
    fake_emotion = FakeEmotionJudge()
    app = create_test_app(
        health_service=StubHealthService(),
        agent_runner=agent_runner,
        agent_auth_secret=TEST_AGENT_SECRET,
        emotion_judge=fake_emotion,
    )
    return TestClient(app), fake_emotion


def test_chat_streams_tokens_and_final_message_with_disclaimer(harness: SimpleNamespace) -> None:
    events = _post_chat(
        harness.client, {"messages": [{"role": "user", "content": "最近总是咳嗽怎么办"}]}
    )

    kinds = [e["event"] for e in events]
    assert kinds[0] == "meta"
    assert kinds[-1] == "done"
    assert kinds.count("token") == 3  # fake 的固定三段 token

    final = events[-2]
    assert final["event"] == "message"
    assert final["data"]["role"] == "assistant"
    assert final["data"]["content"] == "你好，我是小愈。"
    assert final["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    assert final["data"]["effort"] == "disabled"  # 自动档普通对话关闭模型思考
    # 票 44：message 事件携带 emotion（fake 默认降级 calm）；calm 无 soothing_text
    assert final["data"]["emotion"] == "calm"
    assert "soothing_text" not in final["data"]
    # fake emotion judge 收到的是最后一条用户消息
    assert harness.emotion.calls == ["最近总是咳嗽怎么办"]


def test_high_effort_streams_reasoning_around_tool_without_persisting_it_in_message() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"doctors": [{"doctor_id": 2, "name": "周安宁"}]})

    callback = BusinessCallbackClient(
        "http://server-java.test",
        transport=httpx.MockTransport(handler),
        callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(
        disable_streaming=True,
        messages=iter(
            [
                AIMessage(
                    content="",
                    additional_kwargs={"reasoning_content": "先判断合适的科室。"},
                    tool_calls=[
                        ToolCall(
                            name="recommend_doctors",
                            args={"department_name": "心血管内科"},
                            id="thinking-call-1",
                        )
                    ],
                ),
                AIMessage(
                    content="建议尽快就诊。",
                    additional_kwargs={"reasoning_content": "结合医生列表整理建议。"},
                ),
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client,
                {
                    "messages": [{"role": "user", "content": "胸闷应该找谁看"}],
                    "effort": "deep",
                },
            )
    finally:
        asyncio.run(callback.aclose())

    assert [event["event"] for event in events] == [
        "meta",
        "thinking",
        "tool_start",
        "tool_end",
        "doctor_recommendations",
        "thinking",
        "token",
        "message",
        "done",
    ]
    assert [event["data"] for event in events if event["event"] == "thinking"] == [
        "先判断合适的科室。",
        "结合医生列表整理建议。",
    ]
    final = next(event["data"] for event in events if event["event"] == "message")
    assert final["content"] == "建议尽快就诊。"
    assert "先判断" not in final["content"]


def test_non_high_effort_never_exposes_reasoning_content() -> None:
    fake = GenericFakeChatModel(
        disable_streaming=True,
        messages=iter(
            [
                AIMessage(
                    content="直接回复。",
                    additional_kwargs={"reasoning_content": "这段内部思考不应下发。"},
                )
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake)
    client, _ = _build_app(runner)

    with client:
        events = _post_chat(
            client,
            {
                "messages": [{"role": "user", "content": "你好"}],
                "effort": "quick",
            },
        )

    assert "thinking" not in [event["event"] for event in events]
    assert events[-2]["data"]["content"] == "直接回复。"


def test_message_history_is_forwarded_to_agent(harness: SimpleNamespace) -> None:
    _post_chat(
        harness.client,
        {
            "messages": [
                {"role": "user", "content": "我咳嗽三天了"},
                {"role": "assistant", "content": "有发烧吗"},
                {"role": "user", "content": "还开始发烧了"},
            ],
            "health_profile": {
                "id": 31,
                "display_name": "妈妈",
                "gender": "女",
                "birth_date": "1962-05-08",
                "relationship": "母亲",
                "allergies": ["青霉素"],
            },
        },
    )

    history = harness.agent.calls[0]["messages"]
    assert [(m["role"], m["content"]) for m in history] == [
        ("user", "我咳嗽三天了"),
        ("assistant", "有发烧吗"),
        ("user", "还开始发烧了"),
    ]
    assert harness.agent.calls[0]["context"].patient_id == 12
    assert harness.agent.calls[0]["context"].conversation_id == 7
    assert harness.agent.calls[0]["context"].health_profile.display_name == "妈妈"
    assert harness.agent.calls[0]["context"].health_profile.allergies == ["青霉素"]


def test_effort_choice_is_mapped_by_backend(harness: SimpleNamespace) -> None:
    for choice, expected in [("auto", "disabled"), ("quick", "disabled"), ("deep", "high")]:
        harness.agent.calls.clear()
        _post_chat(
            harness.client,
            {"messages": [{"role": "user", "content": "感冒吃什么药"}], "effort": choice},
        )
        assert harness.agent.calls[0]["effort"] == expected
        assert harness.agent.calls[0]["effort"] != "auto"


def test_scenario_drives_auto_effort(harness: SimpleNamespace) -> None:
    _post_chat(
        harness.client,
        {
            "messages": [{"role": "user", "content": "帮我解读这份报告"}],
            "scenario": "interpretation",
        },
    )
    assert harness.agent.calls[0]["effort"] == "high"  # 自动档解读场景映射 high


def test_empty_messages_is_rejected(harness: SimpleNamespace) -> None:
    response = harness.client.post(
        "/api/agent/chat",
        json={"messages": []},
        headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
    )

    assert response.status_code == 422


def test_chat_rejects_calls_without_java_service_credential(harness: SimpleNamespace) -> None:
    response = harness.client.post(
        "/api/agent/chat",
        json={
            "patient_id": 12,
            "conversation_id": 7,
            "messages": [{"role": "user", "content": "你好"}],
        },
    )

    assert response.status_code == 401
    # 文案与 server-java 的 AgentCallbackAuthFilter 保持一致
    assert response.json()["detail"] == "Agent 回调认证失败"

"""对话中的挂号等业务工具调用与卡片投影。"""

import asyncio
import json
from collections.abc import Callable, Iterator, Sequence
from typing import Any

import httpx
from conftest import TEST_AGENT_SECRET, FakeEmotionJudge, StubHealthService
from fastapi.testclient import TestClient
from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, BaseMessage, ToolCall
from langchain_core.outputs import ChatResult
from langchain_core.tools import BaseTool

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


def test_http_agent_streams_structured_cards_from_business_tools_in_call_order() -> None:
    http_calls: list[tuple[str, str]] = []
    http_call_payloads: list[str] = []
    requests_auth_header: list[str] = []
    model_calls: list[list[str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        if request.method == "POST":
            http_call_payloads.append(request.content.decode())
            requests_auth_header.append(request.headers["X-Agent-Callback-Token"])
        if request.url.path.endswith("/recommend"):
            return httpx.Response(
                200,
                json={
                    "doctors": [
                        {
                            "doctor_id": 2,
                            "name": "周安宁",
                            "title": "副主任医师",
                            "specialty": "胸痛评估、心力衰竭",
                            "photo_url": "https://example.com/demo/zhou.jpg",
                            "remaining_slots": 5,
                        }
                    ]
                },
            )
        if request.method == "GET" and request.url.path.endswith("/slots"):
            return httpx.Response(
                200,
                json={
                    "doctor_id": 2,
                    "slots": [
                        {
                            "schedule_id": 9,
                            "schedule_date": "2026-07-29",
                            "time_slot": "上午",
                            "remaining_slots": 3,
                        }
                    ],
                },
            )
        summary_sent = request.method == "POST"
        return httpx.Response(
            200,
            json={
                "appointment_id": 21,
                "schedule_id": 9,
                "doctor_name": "周安宁",
                "status": "已约",
                "summary_sent": summary_sent,
                "notice": "病情摘要已发送给医生" if summary_sent else None,
            },
        )

    callback = BusinessCallbackClient(
        "http://server-java.test",
        transport=httpx.MockTransport(handler),
        callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        messages: Iterator[AIMessage | str]

        def bind_tools(
            self,
            tools: Sequence[dict[str, Any] | type | Callable[..., Any] | BaseTool],
            *,
            tool_choice: str | None = None,
            **kwargs: Any,
        ) -> Any:
            return self

        def _generate(
            self,
            messages: list[BaseMessage],
            stop: list[str] | None = None,
            run_manager: CallbackManagerForLLMRun | None = None,
            **kwargs: Any,
        ) -> ChatResult:
            model_calls.append([message.type for message in messages])
            return super()._generate(messages, stop, run_manager, **kwargs)

    fake = ToolCallingFake(
        disable_streaming=True,
        messages=iter(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        ToolCall(
                            name="recommend_doctors",
                            args={"department_name": "心血管内科"},
                            id="call-1",
                        )
                    ],
                ),
                AIMessage(
                    content="",
                    tool_calls=[
                        ToolCall(name="get_doctor_slots", args={"doctor_id": 2}, id="call-2")
                    ],
                ),
                AIMessage(
                    content="",
                    tool_calls=[
                        ToolCall(
                            name="create_appointment",
                            args={"schedule_id": 9, "condition_summary": "主诉胸闷两天"},
                            id="call-3",
                        )
                    ],
                ),
                "已为你挂号，病情摘要也已发送给医生。",
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "医生还有号吗"}]})
    finally:
        asyncio.run(callback.aclose())

    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "doctor_recommendations",
        "tool_start",
        "tool_end",
        "doctor_slots",
        "tool_start",
        "tool_end",
        "appointment",
        "token",
        "message",
        "done",
    ]
    # trace 事件不带免责声明（非 AI 产出）
    assert events[1]["data"]["tool_name"] == "recommend_doctors"
    assert events[1]["data"]["tool_call_id"] == "call-1"
    assert events[2]["data"]["result"] == "success"
    assert events[3]["data"]["doctors"][0]["name"] == "周安宁"
    assert events[3]["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    assert events[6]["data"]["slots"][0]["schedule_id"] == 9
    assert events[9]["data"]["notice"] == "病情摘要已发送给医生"
    assert events[-2]["data"]["content"] == "已为你挂号，病情摘要也已发送给医生。"
    assert http_calls == [
        (
            "/api/agent/doctors/recommend",
            "department_name=%E5%BF%83%E8%A1%80%E7%AE%A1%E5%86%85%E7%A7%91",
        ),
        ("/api/agent/doctors/2/slots", ""),
        ("/api/agent/appointments", ""),
    ]
    assert json.loads(http_call_payloads[0]) == {
        "patient_id": 12,
        "conversation_id": 7,
        "schedule_id": 9,
        "condition_summary": "主诉胸闷两天",
    }
    assert requests_auth_header == ["shared-secret"]
    assert model_calls == [
        ["system", "human"],
        ["system", "human", "ai", "tool"],
        ["system", "human", "ai", "tool", "ai", "tool"],
        ["system", "human", "ai", "tool", "ai", "tool", "ai", "tool"],
    ]


def test_get_appointment_tool_uses_hidden_patient_context() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"appointments": [{"appointment_id": 21}]})

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
                    tool_calls=[ToolCall(name="get_appointment", args={}, id="call-get")],
                ),
                "你有一条已约挂号。",
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "我的挂号"}]})
    finally:
        asyncio.run(callback.aclose())

    assert requests[0].url.path == "/api/agent/appointments"
    assert requests[0].url.params["patient_id"] == "12"
    assert events[1]["event"] == "tool_start"
    assert events[2]["event"] == "tool_end"
    assert events[3]["event"] == "appointments"

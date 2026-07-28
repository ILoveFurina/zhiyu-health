"""Agent 对话 SSE（HTTP seam，fake Agent 替换 LLM）。

覆盖：SSE 事件序列、免责声明注入、消息历史透传、推理档位映射（auto 不外传）。
"""

import asyncio
import json
from collections.abc import Callable, Iterator, Sequence
from types import SimpleNamespace
from typing import Any

import httpx
from conftest import StubHealthService
from fastapi.testclient import TestClient
from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, BaseMessage, ToolCall
from langchain_core.outputs import ChatResult
from langchain_core.tools import BaseTool

from app.agent.runner import LangGraphAgentRunner
from app.main import create_app
from app.tools.business import BusinessCallbackClient, build_business_tools


def _post_chat(client, payload: dict) -> list[dict]:
    payload = {"patient_id": 12, "conversation_id": 7, **payload}
    with client.stream("POST", "/api/agent/chat", json=payload) as response:
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
    assert final["data"]["effort"] == "low"  # 自动档导诊场景映射 low


def test_message_history_is_forwarded_to_agent(harness: SimpleNamespace) -> None:
    _post_chat(
        harness.client,
        {
            "messages": [
                {"role": "user", "content": "我咳嗽三天了"},
                {"role": "assistant", "content": "有发烧吗"},
                {"role": "user", "content": "还开始发烧了"},
            ]
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


def test_effort_choice_is_mapped_by_backend(harness: SimpleNamespace) -> None:
    for choice, expected in [("auto", "low"), ("quick", "low"), ("deep", "high")]:
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
    response = harness.client.post("/api/agent/chat", json={"messages": []})

    assert response.status_code == 422


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
            return httpx.Response(200, json={"doctors": [{
                "doctor_id": 2,
                "name": "周安宁",
                "title": "副主任医师",
                "specialty": "胸痛评估、心力衰竭",
                "photo_url": "https://example.com/demo/zhou.jpg",
                "remaining_slots": 5,
            }]})
        if request.method == "GET" and request.url.path.endswith("/slots"):
            return httpx.Response(200, json={
                "doctor_id": 2,
                "slots": [{
                    "schedule_id": 9,
                    "schedule_date": "2026-07-29",
                    "time_slot": "上午",
                    "remaining_slots": 3,
                }],
            })
        summary_sent = request.url.path.endswith("/summary")
        return httpx.Response(200, json={
            "appointment_id": 21,
            "schedule_id": 9,
            "doctor_name": "周安宁",
            "status": "已约",
            "summary_sent": summary_sent,
            "notice": "病情摘要已发送给医生" if summary_sent else None,
        })

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
        messages=iter([
            AIMessage(content="", tool_calls=[
                ToolCall(
                    name="recommend_doctors",
                    args={"department_name": "心血管内科"},
                    id="call-1",
                )
            ]),
            AIMessage(content="", tool_calls=[
                ToolCall(name="get_doctor_slots", args={"doctor_id": 2}, id="call-2")
            ]),
            AIMessage(content="", tool_calls=[ToolCall(
                name="create_appointment", args={"schedule_id": 9}, id="call-3"
            )]),
            AIMessage(content="", tool_calls=[ToolCall(
                name="save_condition_summary",
                args={"appointment_id": 21, "condition_summary": "主诉胸闷两天"},
                id="call-4",
            )]),
            "已为你挂号，病情摘要也已发送给医生。",
        ]),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        with TestClient(create_app(health_service=StubHealthService(), agent_runner=runner)) as client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "医生还有号吗"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    assert [event["event"] for event in events] == [
        "meta", "doctor_recommendations", "doctor_slots", "appointment",
        "token", "message", "done"
    ]
    assert events[1]["data"]["doctors"][0]["name"] == "周安宁"
    assert events[1]["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    assert events[2]["data"]["slots"][0]["schedule_id"] == 9
    assert events[3]["data"]["notice"] == "病情摘要已发送给医生"
    assert events[-2]["data"]["content"] == "已为你挂号，病情摘要也已发送给医生。"
    assert http_calls == [
        (
            "/api/agent/doctors/recommend",
            "department_name=%E5%BF%83%E8%A1%80%E7%AE%A1%E5%86%85%E7%A7%91",
        ),
        ("/api/agent/doctors/2/slots", ""),
        ("/api/agent/appointments", ""),
        ("/api/agent/appointments/21/summary", ""),
    ]
    assert json.loads(http_call_payloads[0]) == {
        "patient_id": 12,
        "conversation_id": 7,
        "schedule_id": 9,
    }
    assert json.loads(http_call_payloads[1])["condition_summary"] == "主诉胸闷两天"
    assert requests_auth_header == ["shared-secret", "shared-secret"]
    assert model_calls == [
        ["system", "human"],
        ["system", "human", "ai", "tool"],
        ["system", "human", "ai", "tool", "ai", "tool"],
        ["system", "human", "ai", "tool", "ai", "tool", "ai", "tool"],
        ["system", "human", "ai", "tool", "ai", "tool", "ai", "tool", "ai", "tool"],
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

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="get_appointment", args={}, id="call-get")
        ]),
        "你有一条已约挂号。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        with TestClient(create_app(health_service=StubHealthService(), agent_runner=runner)) as client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "我的挂号"}]})
    finally:
        asyncio.run(callback.aclose())

    assert requests[0].url.path == "/api/agent/appointments"
    assert requests[0].url.params["patient_id"] == "12"
    assert events[1]["event"] == "appointments"

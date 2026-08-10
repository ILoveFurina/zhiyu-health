import asyncio
import json
from collections.abc import Callable, Iterator, Sequence
from typing import Any

import httpx
from conftest import TEST_AGENT_SECRET, FakeEmotionJudge, StubHealthService
from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, ToolCall
from langchain_core.tools import BaseTool
from pydantic import Field

from app.agent.runner import LangGraphAgentRunner
from app.services.directory import CallbackDepartmentDirectory
from app.testing import create_test_app
from app.tools.callback import BusinessCallbackClient
from app.tools.department import build_department_tools

_DEPARTMENTS = [
    {"id": 5, "name": "皮肤科", "category": "皮肤"},
    {"id": 8, "name": "呼吸内科", "category": "内科"},
    {"id": 9, "name": "心血管内科", "category": "内科"},
]


class ToolCallingFake(GenericFakeChatModel):
    messages: Iterator[AIMessage | str]
    tool_choices: list[Any] = Field(default_factory=list)
    bound_tool_names: list[list[str]] = Field(default_factory=list)

    def bind_tools(
        self,
        tools: Sequence[dict[str, Any] | type | Callable[..., Any] | BaseTool],
        *,
        tool_choice: str | None = None,
        **kwargs: Any,
    ) -> Any:
        self.tool_choices.append(tool_choice)
        self.bound_tool_names.append([item.name for item in tools if isinstance(item, BaseTool)])
        return self


def _events(client: TestClient, text: str) -> list[dict[str, Any]]:
    with client.stream(
        "POST",
        "/api/agent/chat",
        json={
            "patient_id": 12,
            "conversation_id": 7,
            "messages": [{"role": "user", "content": text}],
            "effort": "quick",
            "scenario": "triage",
            "knowledge_source": "none",
            "longitude": 113.62,
            "latitude": 34.75,
        },
        headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
    ) as response:
        assert response.status_code == 200
        raw = "".join(response.iter_text())
    result = []
    for frame in raw.split("\n\n"):
        if not frame.strip():
            continue
        lines = frame.strip().split("\n")
        result.append(
            {
                "event": lines[0].removeprefix("event: "),
                "data": json.loads(lines[1].removeprefix("data: ")),
            }
        )
    return result


def _build(fake: ToolCallingFake, handler: Callable[[httpx.Request], httpx.Response]):
    callback = BusinessCallbackClient(
        "http://server-java.test",
        transport=httpx.MockTransport(handler),
        callback_secret="shared-secret",
    )
    directory = CallbackDepartmentDirectory(callback)
    runner = LangGraphAgentRunner(
        lambda effort: fake,
        tools=build_department_tools(directory),
    )
    app = create_test_app(
        health_service=StubHealthService(),
        agent_runner=runner,
        agent_auth_secret=TEST_AGENT_SECRET,
        emotion_judge=FakeEmotionJudge(),
        directory=directory,
    )
    return TestClient(app), callback


def test_model_can_choose_standard_department_slots_tool() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/api/agent/standard-departments":
            return httpx.Response(200, json={"departments": _DEPARTMENTS})
        return httpx.Response(
            200,
            json={
                "standard_department": {"id": 5, "name": "皮肤科"},
                "days": [],
                "doctors": [],
            },
        )

    fake = ToolCallingFake(
        disable_streaming=True,
        messages=iter(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        ToolCall(
                            name="get_standard_department_slots",
                            args={"department_name": "皮肤科"},
                            id="department-slots-1",
                        )
                    ],
                ),
                "已查询到皮肤科号源，请查看卡片。",
            ]
        ),
    )
    client, callback = _build(fake, handler)
    try:
        with client:
            events = _events(client, "我要挂皮肤科")
    finally:
        asyncio.run(callback.aclose())

    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "department_slots",
        "token",
        "message",
        "done",
    ]
    assert events[3]["data"]["standard_department"]["id"] == 5
    assert events[3]["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    assert [request.url.path for request in requests] == [
        "/api/agent/standard-departments",
        "/api/agent/standard-departments/5/slots",
    ]
    assert requests[1].url.params["longitude"] == "113.62"
    assert fake.tool_choices[0] == "required"
    assert fake.bound_tool_names[0] == ["get_standard_department_slots"]
    assert len(fake.tool_choices) == 1


def test_model_can_choose_department_options_tool() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"departments": _DEPARTMENTS})

    fake = ToolCallingFake(
        disable_streaming=True,
        messages=iter(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        ToolCall(
                            name="suggest_standard_departments",
                            args={"department_names": "呼吸内科、心血管内科"},
                            id="department-options-1",
                        )
                    ],
                ),
                "这两个科室都可能，请选择一个查询号源。",
            ]
        ),
    )
    client, callback = _build(fake, handler)
    try:
        with client:
            events = _events(client, "胸闷应该挂什么科")
    finally:
        asyncio.run(callback.aclose())

    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "department_options",
        "token",
        "message",
        "done",
    ]
    assert events[3]["data"]["standard_departments"] == [
        {"id": 8, "name": "呼吸内科"},
        {"id": 9, "name": "心血管内科"},
    ]
    assert [request.url.path for request in requests] == ["/api/agent/standard-departments"]
    assert fake.tool_choices[0] == "required"
    assert fake.bound_tool_names[0] == ["suggest_standard_departments"]
    assert len(fake.tool_choices) == 1


def test_department_options_string_fallback_classified_success_not_error() -> None:
    # LLM 传入目录外科室名时，suggest_standard_departments 返回字符串引导提示（业务降级），
    # 工具执行本身成功，trace result 应记 success 而非 error/TOOL_ERROR_UNKNOWN。
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"departments": _DEPARTMENTS})

    fake = ToolCallingFake(
        disable_streaming=True,
        messages=iter(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        ToolCall(
                            name="suggest_standard_departments",
                            args={"department_names": "宇宙科、量子科"},
                            id="department-options-fallback",
                        )
                    ],
                ),
                "未找到对应科室，以下是目前可挂的科室，请从中选择。",
            ]
        ),
    )
    client, callback = _build(fake, handler)
    try:
        with client:
            events = _events(client, "我要挂宇宙科")
    finally:
        asyncio.run(callback.aclose())

    tool_end = next(e for e in events if e["event"] == "tool_end")
    assert tool_end["data"]["tool_name"] == "suggest_standard_departments"
    assert tool_end["data"]["result"] == "success"
    # 字符串降级无结构化摘要
    assert tool_end["data"].get("tool_output_summary") is None

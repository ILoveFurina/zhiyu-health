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
from langchain_core.tools import BaseTool, tool

from app.agent.runner import LangGraphAgentRunner
from app.main import create_app
from app.tools.business import BusinessCallbackClient


def _post_chat(client, payload: dict) -> list[dict]:
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


def test_http_agent_runs_business_tool_callback_before_final_reply() -> None:
    http_calls: list[str] = []
    model_calls: list[list[str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append(request.url.path)
        return httpx.Response(200, json={"remaining_slots": 3})

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler)
    )

    @tool
    async def get_doctor_slots(doctor_id: int) -> dict[str, Any]:
        """查询医生号源。"""
        return await callback.get("/api/agent/slots", {"doctor_id": doctor_id})  # type: ignore[no-any-return]

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
                ToolCall(name="get_doctor_slots", args={"doctor_id": 2}, id="call-1")
            ]),
            "还有 3 个号源。",
        ]),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=[get_doctor_slots])

    try:
        with TestClient(create_app(health_service=StubHealthService(), agent_runner=runner)) as client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "医生还有号吗"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    assert events[-2]["data"]["content"] == "还有 3 个号源。"
    assert http_calls == ["/api/agent/slots"]
    assert model_calls == [
        ["system", "human"],
        ["system", "human", "ai", "tool"],
    ]

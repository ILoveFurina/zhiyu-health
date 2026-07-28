"""业务工具回调 seam：用 HTTPX MockTransport 替代真实业务后端。"""

import asyncio
import json
from collections.abc import AsyncIterator, Callable, Iterator, Sequence
from typing import Any

import httpx
from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, BaseMessage, ToolCall
from langchain_core.outputs import ChatResult
from langchain_core.tools import BaseTool, tool

from app.agent.runner import LangGraphAgentRunner
from app.tools.business import BusinessCallbackClient


def test_business_callback_uses_fake_http_transport_in_order() -> None:
    calls: list[tuple[str, str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content) if request.content else None
        calls.append((request.method, request.url.path, payload))
        return httpx.Response(200, json={"ok": True})

    async def run() -> None:
        client = BusinessCallbackClient(
            "http://server-java.test", transport=httpx.MockTransport(handler)
        )
        try:
            assert await client.get("/api/agent/slots", {"doctor_id": 2}) == {"ok": True}
            assert await client.post("/api/agent/appointments", {"schedule_id": 8}) == {"ok": True}
        finally:
            await client.aclose()

    asyncio.run(run())

    assert calls == [
        ("GET", "/api/agent/slots", None),
        ("POST", "/api/agent/appointments", {"schedule_id": 8}),
    ]


def test_fake_llm_calls_business_tool_then_uses_callback_result() -> None:
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

    async def run() -> str:
        try:
            tokens: AsyncIterator[str] = runner.astream_reply(
                [{"role": "user", "content": "医生还有号吗"}], "low"
            )
            return "".join([token async for token in tokens])
        finally:
            await callback.aclose()

    assert asyncio.run(run()) == "还有 3 个号源。"
    assert http_calls == ["/api/agent/slots"]
    assert model_calls == [
        ["system", "human"],
        ["system", "human", "ai", "tool"],
    ]

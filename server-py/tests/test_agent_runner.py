"""LangGraph 接线测试：用 langchain_core 的 GenericFakeChatModel 验证

create_agent 图、stream_mode="messages" 过滤与消息转换，不触网。
多轮上下文在 HTTP seam 的覆盖见 test_chat_api.py。
"""

import asyncio
from collections.abc import Iterator
from typing import Any

import httpx
from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, BaseMessage, ToolCall
from langchain_core.outputs import ChatResult

from app.agent.runner import AgentContext, LangGraphAgentRunner
from app.tools.business import BusinessCallbackClient, build_business_tools


def _collect(runner: LangGraphAgentRunner, messages: list[dict[str, str]]) -> str:
    async def run() -> str:
        parts = [
            output.data
            async for output in runner.astream_reply(
                messages, "low", AgentContext(patient_id=12, conversation_id=7)
            )
            if output.event == "token" and isinstance(output.data, str)
        ]
        return "".join(parts)

    return asyncio.run(run())


def test_runner_streams_model_reply_tokens() -> None:
    runner = LangGraphAgentRunner(
        lambda effort: GenericFakeChatModel(messages=iter(["多喝温水，注意休息。"]))
    )

    assert _collect(runner, [{"role": "user", "content": "我咳嗽了"}]) == "多喝温水，注意休息。"


def test_runner_feeds_system_prompt_and_full_history_to_model() -> None:
    seen: list[list[tuple[str, Any]]] = []

    class RecordingFakeModel(GenericFakeChatModel):
        messages: Iterator[AIMessage | str]

        def _generate(
            self,
            messages: list[BaseMessage],
            stop: list[str] | None = None,
            run_manager: CallbackManagerForLLMRun | None = None,
            **kwargs: Any,
        ) -> ChatResult:
            seen.append([(m.type, m.content) for m in messages])
            return super()._generate(messages, stop, run_manager, **kwargs)

    runner = LangGraphAgentRunner(lambda effort: RecordingFakeModel(messages=iter(["好的"])))
    history = [
        {"role": "user", "content": "我咳嗽三天了"},
        {"role": "assistant", "content": "有发烧吗？"},
        {"role": "user", "content": "昨晚开始发烧"},
    ]

    assert _collect(runner, history) == "好的"

    assert len(seen) == 1
    received = seen[0]
    assert received[0][0] == "system"  # 人设 system prompt 在最前
    assert "先回应用户的感受" in received[0][1]
    assert "recommend_doctors" in received[0][1]
    assert "get_doctor_slots" in received[0][1]
    assert "find_hospitals" in received[0][1]
    assert received[1:] == [
        ("human", "我咳嗽三天了"),
        ("ai", "有发烧吗？"),
        ("human", "昨晚开始发烧"),
    ]


def test_find_hospitals_reads_coordinates_from_hidden_context() -> None:
    """坐标是可信设备数据，不经模型入参，从 AgentContext 注入取用。"""
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"hospitals": [
            {"hospital_id": 1, "name": "智愈市人民医院", "distance_km": 3.2}
        ]})

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler)
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[ToolCall(name="find_hospitals", args={}, id="call-h")]),
        "已为你找到附近的医院。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    async def run() -> list:
        outputs = []
        async for output in runner.astream_reply(
            [{"role": "user", "content": "附近有什么医院"}],
            "low",
            AgentContext(patient_id=12, conversation_id=7, longitude=121.4737, latitude=31.2304),
        ):
            outputs.append(output)
        return outputs

    outputs = asyncio.run(run())
    asyncio.run(callback.aclose())

    assert requests[0].url.path == "/api/agent/hospitals/nearby"
    assert requests[0].url.params["longitude"] == "121.4737"
    assert requests[0].url.params["latitude"] == "31.2304"
    hospital_events = [o for o in outputs if o.event == "hospital_recommendations"]
    assert hospital_events[0].data["hospitals"][0]["name"] == "智愈市人民医院"


def test_find_hospitals_degrades_without_location() -> None:
    """拒绝定位时不回调 server-java，直接返回降级提示。"""
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"hospitals": []})

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler)
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[ToolCall(name="find_hospitals", args={}, id="call-h")]),
        "请先授权定位或手动选择区域。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    async def run() -> list:
        outputs = []
        async for output in runner.astream_reply(
            [{"role": "user", "content": "附近医院"}],
            "low",
            AgentContext(patient_id=12, conversation_id=7),  # 无坐标
        ):
            outputs.append(output)
        return outputs

    outputs = asyncio.run(run())
    asyncio.run(callback.aclose())

    assert requests == []  # 未授权定位不查库
    hospital_events = [o for o in outputs if o.event == "hospital_recommendations"]
    assert hospital_events[0].data["need_location"] is True
    assert hospital_events[0].data["hospitals"] == []

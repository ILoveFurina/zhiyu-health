"""Agent 对话 SSE（HTTP seam，fake Agent 替换 LLM）。

覆盖：SSE 事件序列、免责声明注入、消息历史透传、推理档位映射（auto 不外传）。
"""

import asyncio
import json
from collections.abc import Callable, Iterator, Sequence
from types import SimpleNamespace
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

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(
            content="",
            additional_kwargs={"reasoning_content": "先判断合适的科室。"},
            tool_calls=[ToolCall(
                name="recommend_doctors",
                args={"department_name": "心血管内科"},
                id="thinking-call-1",
            )],
        ),
        AIMessage(
            content="建议尽快就诊。",
            additional_kwargs={"reasoning_content": "结合医生列表整理建议。"},
        ),
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {
                "messages": [{"role": "user", "content": "胸闷应该找谁看"}],
                "effort": "deep",
            })
    finally:
        asyncio.run(callback.aclose())

    assert [event["event"] for event in events] == [
        "meta", "thinking", "tool_start", "tool_end", "doctor_recommendations",
        "thinking", "token", "message", "done",
    ]
    assert [event["data"] for event in events if event["event"] == "thinking"] == [
        "先判断合适的科室。", "结合医生列表整理建议。",
    ]
    final = next(event["data"] for event in events if event["event"] == "message")
    assert final["content"] == "建议尽快就诊。"
    assert "先判断" not in final["content"]


def test_non_high_effort_never_exposes_reasoning_content() -> None:
    fake = GenericFakeChatModel(disable_streaming=True, messages=iter([
        AIMessage(
            content="直接回复。",
            additional_kwargs={"reasoning_content": "这段内部思考不应下发。"},
        )
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake)
    client, _ = _build_app(runner)

    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "你好"}],
            "effort": "quick",
        })

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
        summary_sent = request.method == "POST"
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
                name="create_appointment",
                args={"schedule_id": 9, "condition_summary": "主诉胸闷两天"},
                id="call-3",
            )]),
            "已为你挂号，病情摘要也已发送给医生。",
        ]),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "医生还有号吗"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    assert [event["event"] for event in events] == [
        "meta",
        "tool_start", "tool_end", "doctor_recommendations",
        "tool_start", "tool_end", "doctor_slots",
        "tool_start", "tool_end", "appointment",
        "token", "message", "done"
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

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="get_appointment", args={}, id="call-get")
        ]),
        "你有一条已约挂号。",
    ]))
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


def test_patient_agent_only_explains_general_medication_knowledge() -> None:
    requests: list[httpx.Request] = []
    bound_tool_names: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(500)

    callback = BusinessCallbackClient(
        "http://server-java.test",
        transport=httpx.MockTransport(handler),
        callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            bound_tool_names.extend(tool.name for tool in tools)
            return self

    fake = ToolCallingFake(
        disable_streaming=True,
        messages=iter(["阿莫西林属于青霉素类抗菌药。是否适合你服用，请咨询医生或药师。"]),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {
                "messages": [{"role": "user", "content": "阿莫西林是什么药"}],
                "health_profile": {
                    "id": 31,
                    "display_name": "本人",
                    "gender": "女",
                    "birth_date": "1990-01-01",
                    "relationship": "本人",
                    "allergies": ["青霉素"],
                },
            })
    finally:
        asyncio.run(callback.aclose())

    assert [event["event"] for event in events] == ["meta", "token", "message", "done"]
    assert events[-2]["data"]["content"].endswith("请咨询医生或药师。")
    assert "check_contraindication" not in bound_tool_names
    assert requests == []


def test_tool_callback_failure_degrades_to_model_explanation_without_breaking_stream() -> None:
    """票 33 回归：业务回调失败（409 售罄）必须回到模型解释，不得掐断 SSE 流。

    ToolNode 默认只兜参数校验错误，执行期异常穿透图会在响应中途掐断连接
    （server-java 侧表现为 PrematureCloseException）；工具须把失败规整为错误文本，
    由模型向用户解释，且不投影卡片。
    """
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/recommend"):
            return httpx.Response(200, json={"doctors": [{
                "doctor_id": 2, "name": "周安宁", "title": "副主任医师",
                "specialty": "胸痛评估、心力衰竭", "photo_url": "https://example.com/demo/zhou.jpg",
                "remaining_slots": 0,
            }]})
        if request.url.path.endswith("/slots"):
            return httpx.Response(200, json={
                "doctor_id": 2,
                "slots": [{"schedule_id": 9, "schedule_date": "2026-07-29",
                           "time_slot": "上午", "remaining_slots": 0}],
            })
        # 号源售罄：server-java 确定性业务拒绝（ApiErrorBody 形状 {"detail": ...}）
        return httpx.Response(409, json={"detail": "号源已约满"})

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
            ToolCall(name="recommend_doctors", args={"department_name": "心血管内科"}, id="call-1")
        ]),
        AIMessage(content="", tool_calls=[
            ToolCall(name="get_doctor_slots", args={"doctor_id": 2}, id="call-2")
        ]),
        AIMessage(content="", tool_calls=[ToolCall(
            name="create_appointment",
            args={"schedule_id": 9, "condition_summary": "主诉胸闷两天"},
            id="call-3",
        )]),
        "今天上午的号已约满，建议改约下午或其他医生。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "帮我挂周安宁医生今天上午的号"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    # 流完整到达 done；失败的预约不投影 appointment 卡片，模型解释替代。
    # 三次工具调用各自 tool_start/tool_end：失败的 create_appointment 仍发 tool_end，
    # 但 result 为 success（工具返回了结构化错误体，由模型解释；不投影卡片）。
    assert [event["event"] for event in events] == [
        "meta",
        "tool_start", "tool_end", "doctor_recommendations",
        "tool_start", "tool_end", "doctor_slots",
        "tool_start", "tool_end",
        "token", "message", "done"
    ]
    assert events[-2]["data"]["content"] == "今天上午的号已约满，建议改约下午或其他医生。"
    assert events[-2]["data"]["disclaimer"] == "仅供参考，不替代医生诊断"

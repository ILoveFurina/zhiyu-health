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
from app.main import create_app
from app.tools.business import BusinessCallbackClient, build_business_tools


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
    app = create_app(
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


def test_search_medications_projects_card_and_forwards_name() -> None:
    """票 75：search_medications 按药名查 OTC，转发 name 参数并投影 medications 卡片。"""
    http_calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        return httpx.Response(200, json={"medications": [{
            "medication_id": 2, "name": "布洛芬缓释胶囊", "generic_name": "布洛芬",
            "specification": "0.3g*20粒", "price": 22.00, "stock": 280, "is_active": True,
        }]})

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler), callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="search_medications", args={"name": "布洛芬"}, id="call-search")
        ]),
        "已为你找到布洛芬缓释胶囊。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "我想买布洛芬"}]})
    finally:
        asyncio.run(callback.aclose())

    assert [event["event"] for event in events] == [
        "meta", "tool_start", "tool_end", "medications", "token", "message", "done"
    ]
    assert http_calls == [("/api/agent/medications", "name=%E5%B8%83%E6%B4%9B%E8%8A%AC")]
    assert events[3]["data"]["medications"][0]["name"] == "布洛芬缓释胶囊"
    assert events[3]["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    assert events[-2]["data"]["content"] == "已为你找到布洛芬缓释胶囊。"


def test_list_approved_prescriptions_uses_hidden_patient_context() -> None:
    """票 75：list_approved_prescriptions 从可信上下文取 patient_id（模型不可见）。

    票 78：单张处方不再投影 prescriptions 选择卡（spec 要求「单张直通确认卡」），
    工具结果只回到模型，由模型按提示词直接调 prepare_drug_order（此处 fake 仅回文字验证上下文注入）。
    """
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"prescriptions": [{
            "prescription_id": 5, "doctor_name": "周安宁",
            "source_type": "APPOINTMENT", "source_type_label": "线下接诊", "date": "2026-07-29",
            "items": [{"medication_id": 1, "name": "阿莫西林胶囊", "specification": "0.25g*24粒",
                       "dosage": "每次1粒", "frequency": "每日三次", "duration": "7天"}],
        }]})

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler), callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="list_approved_prescriptions", args={}, id="call-list")
        ]),
        "你有一张已审核的处方。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "我有哪些处方可以买药"}]})
    finally:
        asyncio.run(callback.aclose())

# 患者身份来自上下文 patient_id=12，非模型参数
    assert requests[0].url.path == "/api/agent/prescriptions"
    assert requests[0].url.params["patient_id"] == "12"
    # 单张处方按 spec 不投影选择卡：事件序列无 prescriptions 卡，工具结果只回到模型解释
    assert [event["event"] for event in events] == [
        "meta", "tool_start", "tool_end", "token", "message", "done"
    ]


def test_prepare_drug_order_otc_projects_card_without_deducting_stock() -> None:
    """票 75：prepare_drug_order（OTC）只读测算不扣库存，投影 drug_order_prepare 卡片。

    断言 server-java 被调用一次（prepare），且 medications 库存端点从未被调用扣减；
    返回的确认卡携带单价/数量/小计/库存可用性。
    """
    http_calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        return httpx.Response(200, json={
            "source": "OTC", "prescription_id": None,
            "items": [{
                "medication_id": 2, "name": "布洛芬缓释胶囊", "specification": "0.3g*20粒",
                "quantity": 3, "unit_price": 22.00, "subtotal": 66.00,
                "stock": 280, "available": True,
            }],
            "total_amount": 66.00, "payable_amount": 66.00,
            "prescription_source_type": None, "doctor_name": None, "prescription_date": None,
        })

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler), callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="prepare_drug_order",
                     args={"medication_id": 2, "quantity": 3}, id="call-prepare")
        ]),
        "已为你准备好购药确认，3 盒布洛芬缓释胶囊共 66 元。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "帮我确认买3盒布洛芬"}]})
    finally:
        asyncio.run(callback.aclose())

    # 只读 prepare：仅一次 GET，无扣库存/建订单的后续调用
    assert len(http_calls) == 1
    assert http_calls[0][0] == "/api/agent/drug-orders/prepare"
    # patient_id 从上下文注入，medication_id + quantity 由模型传入
    assert "medication_id=2" in http_calls[0][1]
    assert "quantity=3" in http_calls[0][1]
    assert "patient_id=12" in http_calls[0][1]
    assert [event["event"] for event in events] == [
        "meta", "tool_start", "tool_end", "drug_order_prepare", "token", "message", "done"
    ]
    card = events[3]["data"]
    assert card["source"] == "OTC"
    assert card["items"][0]["quantity"] == 3
    assert card["items"][0]["subtotal"] == 66.00
    assert card["items"][0]["available"] is True
    assert card["total_amount"] == 66.00


def test_prepare_drug_order_prescription_branch_forwards_prescription_id() -> None:
    """票 75：prepare_drug_order 处方药分支传 prescription_id（非 medication_id），投影 drug_order_prepare。"""
    http_calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        return httpx.Response(200, json={
            "source": "PRESCRIPTION", "prescription_id": 5,
            "items": [{
                "medication_id": 1, "name": "阿莫西林胶囊", "specification": "0.25g*24粒",
                "quantity": 1, "unit_price": 18.50, "subtotal": 18.50,
                "stock": 320, "available": True,
            }],
            "total_amount": 18.50, "payable_amount": 18.50,
            "prescription_source_type": "APPOINTMENT", "doctor_name": "周安宁", "prescription_date": "2026-07-29",
        })

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler), callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="prepare_drug_order", args={"prescription_id": 5}, id="call-prepare-rx")
        ]),
        "已按处方为你准备好购药确认。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "按处方帮我准备买药"}]})
    finally:
        asyncio.run(callback.aclose())

    assert http_calls[0][0] == "/api/agent/drug-orders/prepare"
    assert "prescription_id=5" in http_calls[0][1]
    # 处方药分支不带 medication_id / quantity
    assert "medication_id" not in http_calls[0][1]
    assert [event["event"] for event in events] == [
        "meta", "tool_start", "tool_end", "drug_order_prepare", "token", "message", "done"
    ]
    assert events[3]["data"]["source"] == "PRESCRIPTION"
    assert events[3]["data"]["prescription_id"] == 5


def test_zero_approved_prescriptions_suppresses_card_and_prompts_guidance() -> None:
    """票 78：list_approved_prescriptions 返回空列表时抑制 prescriptions 卡片不下发，
    让模型按提示词文字引导「暂无已审核处方，可先发起问诊或挂号让医生开方」。

    事件序列不含 prescriptions 卡，但仍含 tool_start/tool_end（查询本身成功，只是无数据）。
    """
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"prescriptions": []})

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler), callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="list_approved_prescriptions", args={}, id="call-list-empty")
        ]),
        "您暂无已审核处方，可先发起问诊或挂号让医生开方。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "按处方买药"}]})
    finally:
        asyncio.run(callback.aclose())

    # 零处方不下发卡片：事件序列无 prescriptions，工具调用照常返回
    assert [event["event"] for event in events] == [
        "meta", "tool_start", "tool_end", "token", "message", "done"
    ]
    # 最后一条 message 文案即引导语
    assert "暂无已审核处方" in events[-2]["data"]["content"]


def test_multiple_approved_prescriptions_projects_selection_card() -> None:
    """票 78：list_approved_prescriptions 返回多张 APPROVED 处方时投影 prescriptions 选择卡，
    payload 含 doctor_name/date/source_type_label/items 供端侧渲染选择列表。
    """
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"prescriptions": [
            {
                "prescription_id": 5, "doctor_name": "周安宁",
                "source_type": "APPOINTMENT", "source_type_label": "线下接诊", "date": "2026-07-29",
                "items": [{"medication_id": 1, "name": "阿莫西林胶囊", "specification": "0.25g*24粒",
                           "dosage": "每次1粒", "frequency": "每日三次", "duration": "7天"}],
            },
            {
                "prescription_id": 8, "doctor_name": "陈志远",
                "source_type": "ONLINE_CONSULTATION", "source_type_label": "在线问诊", "date": "2026-08-02",
                "items": [{"medication_id": 3, "name": "头孢克肟分散片", "specification": "0.1g*6片",
                           "dosage": "每次1片", "frequency": "每日两次", "duration": "5天"}],
            },
        ]})

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler), callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="list_approved_prescriptions", args={}, id="call-list-multi")
        ]),
        "您有多张已审核处方，请选择一张继续购药。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "按处方买药"}]})
    finally:
        asyncio.run(callback.aclose())

    # 多处方投影 prescriptions 选择卡，数据完整（2 张处方含明细）
    assert [event["event"] for event in events] == [
        "meta", "tool_start", "tool_end", "prescriptions", "token", "message", "done"
    ]
    card = events[3]["data"]["prescriptions"]
    assert len(card) == 2
    assert card[0]["prescription_id"] == 5
    assert card[0]["doctor_name"] == "周安宁"
    assert card[0]["source_type_label"] == "线下接诊"
    assert card[0]["items"][0]["name"] == "阿莫西林胶囊"
    assert card[1]["prescription_id"] == 8
    assert card[1]["items"][0]["specification"] == "0.1g*6片"


def test_single_approved_prescription_skips_selection_card_and_prepares_confirm() -> None:
    """票 78：list_approved_prescriptions 返回单张时按 spec「单张直通确认卡」不投影选择卡，
    模型按提示词直接调 prepare_drug_order(prescription_id=该处方 id) 装配确认卡。
    """
    http_calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        if request.url.path == "/api/agent/prescriptions":
            return httpx.Response(200, json={"prescriptions": [{
                "prescription_id": 5, "doctor_name": "周安宁",
                "source_type": "APPOINTMENT", "source_type_label": "线下接诊", "date": "2026-07-29",
                "items": [{"medication_id": 1, "name": "阿莫西林胶囊", "specification": "0.25g*24粒",
                           "dosage": "每次1粒", "frequency": "每日三次", "duration": "7天"}],
            }]})
        # prepare 调用默认返回确认卡数据
        return httpx.Response(200, json={
            "source": "PRESCRIPTION", "prescription_id": 5,
            "items": [{
                "medication_id": 1, "name": "阿莫西林胶囊", "specification": "0.25g*24粒",
                "quantity": 1, "unit_price": 18.50, "subtotal": 18.50,
                "stock": 320, "available": True,
            }],
            "total_amount": 18.50, "payable_amount": 18.50,
            "prescription_source_type": "APPOINTMENT", "doctor_name": "周安宁", "prescription_date": "2026-07-29",
        })

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler), callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="list_approved_prescriptions", args={}, id="call-list-single")
        ]),
        AIMessage(content="", tool_calls=[
            ToolCall(name="prepare_drug_order", args={"prescription_id": 5}, id="call-prepare-single")
        ]),
        "已按您的处方准备好购药确认。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "按处方买药"}]})
    finally:
        asyncio.run(callback.aclose())

    # 单张处方：先查处方（无选择卡），再直接 prepare 装配确认卡（drug_order_prepare）
    assert [event["event"] for event in events] == [
        "meta", "tool_start", "tool_end",
        "tool_start", "tool_end", "drug_order_prepare",
        "token", "message", "done",
    ]
    # 两次回调：prescriptions + prepare，prepare 携带 prescription_id=5
    assert http_calls[0][0] == "/api/agent/prescriptions"
    assert http_calls[1][0] == "/api/agent/drug-orders/prepare"
    assert "prescription_id=5" in http_calls[1][1]
    assert events[5]["data"]["source"] == "PRESCRIPTION"


def test_prescription_id_in_request_drives_prepare_drug_order() -> None:
    """票 78：请求携带 prescription_id 时注入 AgentContext.selected_prescription_id，
    经 SystemMessage 提示模型直接调 prepare_drug_order(prescription_id=<该值>) 装配确认卡，
    不再调 list_approved_prescriptions。事件序列含 drug_order_prepare 卡片。
    """
    http_calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        return httpx.Response(200, json={
            "source": "PRESCRIPTION", "prescription_id": 7,
            "items": [{
                "medication_id": 9, "name": "左氧氟沙星片", "specification": "0.5g*4片",
                "quantity": 1, "unit_price": 32.00, "subtotal": 32.00,
                "stock": 150, "available": True,
            }],
            "total_amount": 32.00, "payable_amount": 32.00,
            "prescription_source_type": "ONLINE_CONSULTATION", "doctor_name": "陈志远", "prescription_date": "2026-08-02",
        })

    callback = BusinessCallbackClient(
        "http://server-java.test", transport=httpx.MockTransport(handler), callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="prepare_drug_order", args={"prescription_id": 7}, id="call-prepare-rx-select")
        ]),
        "已按选定的处方为您准备好购药确认。",
    ]))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {
                "messages": [{"role": "user", "content": "按此处方买药"}],
                "prescription_id": 7,
            })
    finally:
        asyncio.run(callback.aclose())

    # 直接装配确认卡：只调用 prepare，未查 list
    assert [(path, qs) for path, qs in http_calls] == [("/api/agent/drug-orders/prepare", http_calls[0][1])]
    assert "prescription_id=7" in http_calls[0][1]
    assert "/api/agent/prescriptions" not in [path for path, _ in http_calls]
    assert [event["event"] for event in events] == [
        "meta", "tool_start", "tool_end", "drug_order_prepare", "token", "message", "done"
    ]
    assert events[3]["data"]["source"] == "PRESCRIPTION"
    assert events[3]["data"]["prescription_id"] == 7


"""C 端通用药品知识、药品检索与已审核处方查询。"""

import asyncio
import json

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
            events = _post_chat(
                client,
                {
                    "messages": [{"role": "user", "content": "阿莫西林是什么药"}],
                    "health_profile": {
                        "id": 31,
                        "display_name": "本人",
                        "gender": "女",
                        "birth_date": "1990-01-01",
                        "relationship": "本人",
                        "allergies": ["青霉素"],
                    },
                },
            )
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
                            "remaining_slots": 0,
                        }
                    ]
                },
            )
        if request.url.path.endswith("/slots"):
            return httpx.Response(
                200,
                json={
                    "doctor_id": 2,
                    "slots": [
                        {
                            "schedule_id": 9,
                            "schedule_date": "2026-07-29",
                            "time_slot": "上午",
                            "remaining_slots": 0,
                        }
                    ],
                },
            )
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
                "今天上午的号已约满，建议改约下午或其他医生。",
            ]
        ),
    )
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
        "tool_start",
        "tool_end",
        "doctor_recommendations",
        "tool_start",
        "tool_end",
        "doctor_slots",
        "tool_start",
        "tool_end",
        "token",
        "message",
        "done",
    ]
    assert events[-2]["data"]["content"] == "今天上午的号已约满，建议改约下午或其他医生。"
    assert events[-2]["data"]["disclaimer"] == "仅供参考，不替代医生诊断"


def test_search_medications_projects_card_and_forwards_name() -> None:
    """票 77：search_medications 按药名查 OTC，转发 name 参数并投影 medications 卡片。"""
    http_calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        return httpx.Response(
            200,
            json={
                "medications": [
                    {
                        "medication_id": 2,
                        "name": "布洛芬缓释胶囊",
                        "generic_name": "布洛芬",
                        "specification": "0.3g*20粒",
                        "price": 22.00,
                        "stock": 280,
                        "is_active": True,
                    }
                ]
            },
        )

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
                    tool_calls=[
                        ToolCall(
                            name="search_medications", args={"name": "布洛芬"}, id="call-search"
                        )
                    ],
                ),
                "已为你找到布洛芬缓释胶囊。",
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "我想买布洛芬"}]})
    finally:
        asyncio.run(callback.aclose())

    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "medications",
        "token",
        "message",
        "done",
    ]
    assert http_calls == [("/api/agent/medications", "name=%E5%B8%83%E6%B4%9B%E8%8A%AC")]
    assert events[3]["data"]["medications"][0]["name"] == "布洛芬缓释胶囊"
    assert events[3]["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    assert events[-2]["data"]["content"] == "已为你找到布洛芬缓释胶囊。"


def test_list_approved_prescriptions_uses_hidden_patient_context() -> None:
    """票 77：list_approved_prescriptions 从可信上下文取 patient_id（模型不可见）。

    票 80：单张处方不再投影 prescriptions 选择卡（spec 要求「单张直通确认卡」），
    工具结果只回到模型，由模型按提示词直接调 prepare_drug_order（此处 fake 仅回文字验证上下文注入）。
    """
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={
                "prescriptions": [
                    {
                        "prescription_id": 5,
                        "doctor_name": "周安宁",
                        "source_type": "APPOINTMENT",
                        "source_type_label": "线下接诊",
                        "date": "2026-07-29",
                        "items": [
                            {
                                "medication_id": 1,
                                "name": "阿莫西林胶囊",
                                "specification": "0.25g*24粒",
                                "dosage": "每次1粒",
                                "frequency": "每日三次",
                                "duration": "7天",
                            }
                        ],
                    }
                ]
            },
        )

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
                    tool_calls=[
                        ToolCall(name="list_approved_prescriptions", args={}, id="call-list")
                    ],
                ),
                "你有一张已审核的处方。",
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "我有哪些处方可以买药"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    # 患者身份来自上下文 patient_id=12，非模型参数
    assert requests[0].url.path == "/api/agent/prescriptions"
    assert requests[0].url.params["patient_id"] == "12"
    # 单张处方按 spec 不投影选择卡：事件序列无 prescriptions 卡，工具结果只回到模型解释
    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "token",
        "message",
        "done",
    ]

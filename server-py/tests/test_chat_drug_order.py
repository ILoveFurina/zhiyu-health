"""OTC 与处方药下单准备流程及结构化卡片投影。"""

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


def test_prepare_drug_order_otc_projects_card_without_deducting_stock() -> None:
    """票 77：prepare_drug_order（OTC）只读测算不扣库存，投影 drug_order_prepare 卡片。

    断言 server-java 被调用一次（prepare），且 medications 库存端点从未被调用扣减；
    返回的确认卡携带单价/数量/小计/库存可用性。
    """
    http_calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        return httpx.Response(
            200,
            json={
                "source": "OTC",
                "prescription_id": None,
                "items": [
                    {
                        "medication_id": 2,
                        "name": "布洛芬缓释胶囊",
                        "specification": "0.3g*20粒",
                        "quantity": 3,
                        "unit_price": 22.00,
                        "subtotal": 66.00,
                        "stock": 280,
                        "available": True,
                    }
                ],
                "total_amount": 66.00,
                "payable_amount": 66.00,
                "prescription_source_type": None,
                "doctor_name": None,
                "prescription_date": None,
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
                            name="prepare_drug_order",
                            args={"medication_id": 2, "quantity": 3},
                            id="call-prepare",
                        )
                    ],
                ),
                "已为你准备好购药确认，3 盒布洛芬缓释胶囊共 66 元。",
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "帮我确认买3盒布洛芬"}]}
            )
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
        "meta",
        "tool_start",
        "tool_end",
        "drug_order_prepare",
        "token",
        "message",
        "done",
    ]
    card = events[3]["data"]
    assert card["source"] == "OTC"
    assert card["items"][0]["quantity"] == 3
    assert card["items"][0]["subtotal"] == 66.00
    assert card["items"][0]["available"] is True
    assert card["total_amount"] == 66.00


def test_prepare_drug_order_prescription_branch_forwards_prescription_id() -> None:
    """票 77：prepare_drug_order 处方药分支传 prescription_id（非 medication_id），投影 drug_order_prepare。"""
    http_calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        return httpx.Response(
            200,
            json={
                "source": "PRESCRIPTION",
                "prescription_id": 5,
                "items": [
                    {
                        "medication_id": 1,
                        "name": "阿莫西林胶囊",
                        "specification": "0.25g*24粒",
                        "quantity": 1,
                        "unit_price": 18.50,
                        "subtotal": 18.50,
                        "stock": 320,
                        "available": True,
                    }
                ],
                "total_amount": 18.50,
                "payable_amount": 18.50,
                "prescription_source_type": "APPOINTMENT",
                "doctor_name": "周安宁",
                "prescription_date": "2026-07-29",
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
                            name="prepare_drug_order",
                            args={"prescription_id": 5},
                            id="call-prepare-rx",
                        )
                    ],
                ),
                "已按处方为你准备好购药确认。",
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "按处方帮我准备买药"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    assert http_calls[0][0] == "/api/agent/drug-orders/prepare"
    assert "prescription_id=5" in http_calls[0][1]
    # 处方药分支不带 medication_id / quantity
    assert "medication_id" not in http_calls[0][1]
    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "drug_order_prepare",
        "token",
        "message",
        "done",
    ]
    assert events[3]["data"]["source"] == "PRESCRIPTION"
    assert events[3]["data"]["prescription_id"] == 5


def test_zero_approved_prescriptions_suppresses_card_and_prompts_guidance() -> None:
    """票 80：list_approved_prescriptions 返回空列表时抑制 prescriptions 卡片不下发，
    让模型按提示词文字引导「暂无已审核处方，可先发起问诊或挂号让医生开方」。

    事件序列不含 prescriptions 卡，但仍含 tool_start/tool_end（查询本身成功，只是无数据）。
    """

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"prescriptions": []})

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
                        ToolCall(name="list_approved_prescriptions", args={}, id="call-list-empty")
                    ],
                ),
                "您暂无已审核处方，可先发起问诊或挂号让医生开方。",
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "按处方买药"}]})
    finally:
        asyncio.run(callback.aclose())

    # 零处方不下发卡片：事件序列无 prescriptions，工具调用照常返回
    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "token",
        "message",
        "done",
    ]
    # 最后一条 message 文案即引导语
    assert "暂无已审核处方" in events[-2]["data"]["content"]


def test_multiple_approved_prescriptions_projects_selection_card() -> None:
    """票 80：list_approved_prescriptions 返回多张 APPROVED 处方时投影 prescriptions 选择卡，
    payload 含 doctor_name/date/source_type_label/items 供端侧渲染选择列表。
    """

    def handler(request: httpx.Request) -> httpx.Response:
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
                    },
                    {
                        "prescription_id": 8,
                        "doctor_name": "陈志远",
                        "source_type": "ONLINE_CONSULTATION",
                        "source_type_label": "在线问诊",
                        "date": "2026-08-02",
                        "items": [
                            {
                                "medication_id": 3,
                                "name": "头孢克肟分散片",
                                "specification": "0.1g*6片",
                                "dosage": "每次1片",
                                "frequency": "每日两次",
                                "duration": "5天",
                            }
                        ],
                    },
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
                        ToolCall(name="list_approved_prescriptions", args={}, id="call-list-multi")
                    ],
                ),
                "您有多张已审核处方，请选择一张继续购药。",
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "按处方买药"}]})
    finally:
        asyncio.run(callback.aclose())

    # 多处方投影 prescriptions 选择卡，数据完整（2 张处方含明细）
    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "prescriptions",
        "token",
        "message",
        "done",
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
    """票 80：list_approved_prescriptions 返回单张时按 spec「单张直通确认卡」不投影选择卡，
    模型按提示词直接调 prepare_drug_order(prescription_id=该处方 id) 装配确认卡。
    """
    http_calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        if request.url.path == "/api/agent/prescriptions":
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
        # prepare 调用默认返回确认卡数据
        return httpx.Response(
            200,
            json={
                "source": "PRESCRIPTION",
                "prescription_id": 5,
                "items": [
                    {
                        "medication_id": 1,
                        "name": "阿莫西林胶囊",
                        "specification": "0.25g*24粒",
                        "quantity": 1,
                        "unit_price": 18.50,
                        "subtotal": 18.50,
                        "stock": 320,
                        "available": True,
                    }
                ],
                "total_amount": 18.50,
                "payable_amount": 18.50,
                "prescription_source_type": "APPOINTMENT",
                "doctor_name": "周安宁",
                "prescription_date": "2026-07-29",
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
                        ToolCall(name="list_approved_prescriptions", args={}, id="call-list-single")
                    ],
                ),
                AIMessage(
                    content="",
                    tool_calls=[
                        ToolCall(
                            name="prepare_drug_order",
                            args={"prescription_id": 5},
                            id="call-prepare-single",
                        )
                    ],
                ),
                "已按您的处方准备好购药确认。",
            ]
        ),
    )
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "按处方买药"}]})
    finally:
        asyncio.run(callback.aclose())

    # 单张处方：先查处方（无选择卡），再直接 prepare 装配确认卡（drug_order_prepare）
    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "tool_start",
        "tool_end",
        "drug_order_prepare",
        "token",
        "message",
        "done",
    ]
    # 两次回调：prescriptions + prepare，prepare 携带 prescription_id=5
    assert http_calls[0][0] == "/api/agent/prescriptions"
    assert http_calls[1][0] == "/api/agent/drug-orders/prepare"
    assert "prescription_id=5" in http_calls[1][1]
    assert events[5]["data"]["source"] == "PRESCRIPTION"


def test_prescription_id_in_request_drives_prepare_drug_order() -> None:
    """票 80：请求携带 prescription_id 时注入 AgentContext.selected_prescription_id，
    经 SystemMessage 提示模型直接调 prepare_drug_order(prescription_id=<该值>) 装配确认卡，
    不再调 list_approved_prescriptions。事件序列含 drug_order_prepare 卡片。
    """
    http_calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, request.url.query.decode()))
        return httpx.Response(
            200,
            json={
                "source": "PRESCRIPTION",
                "prescription_id": 7,
                "items": [
                    {
                        "medication_id": 9,
                        "name": "左氧氟沙星片",
                        "specification": "0.5g*4片",
                        "quantity": 1,
                        "unit_price": 32.00,
                        "subtotal": 32.00,
                        "stock": 150,
                        "available": True,
                    }
                ],
                "total_amount": 32.00,
                "payable_amount": 32.00,
                "prescription_source_type": "ONLINE_CONSULTATION",
                "doctor_name": "陈志远",
                "prescription_date": "2026-08-02",
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
                            name="prepare_drug_order",
                            args={"prescription_id": 7},
                            id="call-prepare-rx-select",
                        )
                    ],
                ),
                "已按选定的处方为您准备好购药确认。",
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
                    "messages": [{"role": "user", "content": "按此处方买药"}],
                    "prescription_id": 7,
                },
            )
    finally:
        asyncio.run(callback.aclose())

    # 直接装配确认卡：只调用 prepare，未查 list
    assert [(path, qs) for path, qs in http_calls] == [
        ("/api/agent/drug-orders/prepare", http_calls[0][1])
    ]
    assert "prescription_id=7" in http_calls[0][1]
    assert "/api/agent/prescriptions" not in [path for path, _ in http_calls]
    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "drug_order_prepare",
        "token",
        "message",
        "done",
    ]
    assert events[3]["data"]["source"] == "PRESCRIPTION"
    assert events[3]["data"]["prescription_id"] == 7

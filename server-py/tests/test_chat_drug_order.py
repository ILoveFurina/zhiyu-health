"""购药预览卡流程：OTC/处方药双路径、处方三态与卡片白名单投影（票 77/79/80/88）。"""

import asyncio
import json
from typing import Any

import httpx
from conftest import TEST_AGENT_SECRET, FakeEmotionJudge, StubHealthService
from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, ToolCall

from app.agent.runner import LangGraphAgentRunner
from app.testing import create_test_app
from app.tools.business import build_business_tools
from app.tools.callback import BusinessCallbackClient

_PREVIEW_NOTICE = "价格库存以确认页为准"
_DISCLAIMER = "仅供参考，不替代医生诊断"

# 票 88：预览卡白名单——公共字段 + 处方药路径的处方来源事实；disclaimer 由 SSE 出口注入。
_OTC_CARD_KEYS = {"source", "prescription_id", "items", "price_stock_notice", "disclaimer"}
_RX_CARD_KEYS = _OTC_CARD_KEYS | {
    "doctor_name",
    "prescription_date",
    "hospital_name",
    "campus_name",
    "pharmacy_name",
}


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


def _build_runner(handler, messages) -> tuple[LangGraphAgentRunner, BusinessCallbackClient]:
    callback = BusinessCallbackClient(
        "http://server-java.test",
        transport=httpx.MockTransport(handler),
        callback_secret="shared-secret",
    )

    class ToolCallingFake(GenericFakeChatModel):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            return self

    fake = ToolCallingFake(disable_streaming=True, messages=iter(messages))
    runner = LangGraphAgentRunner(lambda effort: fake, tools=build_business_tools(callback))
    return runner, callback


def _rx_prepare_payload(
    prescription_id: int = 5,
    doctor_name: str = "周安宁",
    prescription_date: str = "2026-07-29",
) -> dict[str, Any]:
    """票 88 新 prepare 形状：处方固化院区 + 锁定院区药房 + 实时价格库存（只回模型，不落卡）。"""
    return {
        "source": "PRESCRIPTION",
        "prescription_id": prescription_id,
        "doctor_name": doctor_name,
        "prescription_date": prescription_date,
        "hospital_name": "智愈人民医院",
        "campus_name": "滨江院区",
        "campus_address": "滨江区智愈路 88 号",
        "pharmacy": {
            "id": 11,
            "display_name": "滨江院区药房",
            "delivery_fee": 5.00,
            "estimated_delivery_minutes": 45,
        },
        "items": [
            {
                "medication_id": 1,
                "name": "阿莫西林胶囊",
                "specification": "0.25g*24粒",
                "quantity": 1,
                "unit_price": 18.50,
                "stock": 320,
                "available": True,
            }
        ],
        "medication_amount": 18.50,
    }


def _otc_prepare_payload(medication_id: int = 2, quantity: int = 3) -> dict[str, Any]:
    """票 88 otc-prepare 形状：只回明细回声（medication_id/name/specification/quantity）。"""
    return {
        "items": [
            {
                "medication_id": medication_id,
                "name": "布洛芬缓释胶囊",
                "specification": "0.3g*20粒",
                "quantity": quantity,
            }
        ]
    }


_OTC_CATALOG_ENTRY = {
    "medication_id": 2,
    "name": "布洛芬缓释胶囊",
    "generic_name": "布洛芬",
    "specification": "0.3g*20粒",
    "is_prescription": False,
}

_RX_CATALOG_ENTRY = {
    "medication_id": 1,
    "name": "阿莫西林胶囊",
    "generic_name": "阿莫西林",
    "specification": "0.25g*24粒",
    "is_prescription": True,
}


def test_prepare_drug_order_otc_projects_preview_card_without_deducting_stock() -> None:
    """票 88：prepare_drug_order（OTC）改调 otc-prepare 校验明细，只读不扣库存不建订单，
    投影购药预览卡（drug_order_prepare 事件），卡片按白名单只含来源/明细/固定提示。
    """
    http_calls: list[tuple[str, dict[str, str]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, dict(request.url.params)))
        return httpx.Response(200, json=_otc_prepare_payload())

    runner, callback = _build_runner(
        handler,
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
            "已为你准备好购药预览，点击进入确认页查看实时价格与库存。",
        ],
    )

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "帮我确认买3盒布洛芬"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    # 只读 otc-prepare：仅一次 GET，无扣库存/建订单的后续调用；patient_id 从上下文注入
    assert http_calls == [
        ("/api/agent/drug-orders/otc-prepare", {"patient_id": "12", "items": "2:3"})
    ]
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
    # 白名单：只含来源/明细/固定提示/免责声明，无价格、库存、收货或取药方式字段
    assert set(card) == _OTC_CARD_KEYS
    assert card["source"] == "OTC"
    assert card["prescription_id"] is None
    assert card["items"] == [
        {
            "medication_id": 2,
            "name": "布洛芬缓释胶囊",
            "specification": "0.3g*20粒",
            "quantity": 3,
        }
    ]
    assert card["price_stock_notice"] == _PREVIEW_NOTICE
    assert card["disclaimer"] == _DISCLAIMER


def test_prepare_drug_order_prescription_branch_projects_locked_pharmacy() -> None:
    """票 88：prepare_drug_order 处方药分支传 prescription_id，回调返回锁定院区药房，
    预览卡含医生/日期/医院/院区/药房名，价格库存等实时字段不落卡。
    """
    http_calls: list[tuple[str, dict[str, str]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, dict(request.url.params)))
        return httpx.Response(200, json=_rx_prepare_payload())

    runner, callback = _build_runner(
        handler,
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
            "已按处方为你准备好购药预览。",
        ],
    )

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "按处方帮我准备买药"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    # 处方药分支：只调 prepare，带 prescription_id，不带 medication_id / quantity
    assert http_calls == [
        ("/api/agent/drug-orders/prepare", {"patient_id": "12", "prescription_id": "5"})
    ]
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
    assert set(card) == _RX_CARD_KEYS
    assert card["source"] == "PRESCRIPTION"
    assert card["prescription_id"] == 5
    # 锁定院区药房与处方来源事实入卡
    assert card["pharmacy_name"] == "滨江院区药房"
    assert card["doctor_name"] == "周安宁"
    assert card["prescription_date"] == "2026-07-29"
    assert card["hospital_name"] == "智愈人民医院"
    assert card["campus_name"] == "滨江院区"
    assert card["items"] == [
        {
            "medication_id": 1,
            "name": "阿莫西林胶囊",
            "specification": "0.25g*24粒",
            "quantity": 1,
        }
    ]
    assert card["price_stock_notice"] == _PREVIEW_NOTICE


def test_otc_named_drug_with_quantity_searches_then_prepares_preview() -> None:
    """票 88：OTC 明确药品+数量完整流——先 search_medications 查标准药品目录，
    确认 OTC 后调 prepare_drug_order 经 otc-prepare 校验并产出预览卡（断言工具调用顺序）。
    """
    http_calls: list[tuple[str, dict[str, str]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, dict(request.url.params)))
        if request.url.path == "/api/agent/medications":
            return httpx.Response(200, json={"medications": [_OTC_CATALOG_ENTRY]})
        return httpx.Response(200, json=_otc_prepare_payload())

    runner, callback = _build_runner(
        handler,
        [
            AIMessage(
                content="",
                tool_calls=[
                    ToolCall(name="search_medications", args={"name": "布洛芬"}, id="call-search")
                ],
            ),
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
            "已为你准备好 3 盒布洛芬缓释胶囊的购药预览，点击进入确认页。",
        ],
    )

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "帮我买3盒布洛芬"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    # 工具调用顺序：先查目录（medications 卡），再 otc-prepare（预览卡）
    assert http_calls == [
        ("/api/agent/medications", {"name": "布洛芬"}),
        ("/api/agent/drug-orders/otc-prepare", {"patient_id": "12", "items": "2:3"}),
    ]
    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "medications",
        "tool_start",
        "tool_end",
        "drug_order_prepare",
        "token",
        "message",
        "done",
    ]
    card = events[6]["data"]
    assert set(card) == _OTC_CARD_KEYS
    assert card["source"] == "OTC"
    assert card["items"][0]["medication_id"] == 2
    assert card["items"][0]["quantity"] == 3
    assert card["price_stock_notice"] == _PREVIEW_NOTICE
    assert card["disclaimer"] == _DISCLAIMER


def test_otc_named_drug_without_quantity_asks_before_prepare() -> None:
    """票 88：OTC 只点名药品没给数量时先追问数量——查目录后不调 prepare，不出预览卡。"""
    http_calls: list[tuple[str, dict[str, str]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, dict(request.url.params)))
        return httpx.Response(200, json={"medications": [_OTC_CATALOG_ENTRY]})

    runner, callback = _build_runner(
        handler,
        [
            AIMessage(
                content="",
                tool_calls=[
                    ToolCall(name="search_medications", args={"name": "布洛芬"}, id="call-search")
                ],
            ),
            "有的，布洛芬缓释胶囊 0.3g*20粒。请问您要购买几盒呢？",
        ],
    )

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "我想买布洛芬"}]})
    finally:
        asyncio.run(callback.aclose())

    # 只查目录，未触发 otc-prepare，不出预览卡
    assert http_calls == [("/api/agent/medications", {"name": "布洛芬"})]
    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "medications",
        "token",
        "message",
        "done",
    ]
    assert "几盒" in events[-2]["data"]["content"]


def test_otc_prescription_drug_hit_declines_and_guides_to_prescription() -> None:
    """票 88：点名药品命中处方药（is_prescription=true）时拒绝 OTC 购药，
    不调 prepare，文字引导凭已审核处方购药或咨询医生。
    """
    http_calls: list[tuple[str, dict[str, str]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, dict(request.url.params)))
        return httpx.Response(200, json={"medications": [_RX_CATALOG_ENTRY]})

    runner, callback = _build_runner(
        handler,
        [
            AIMessage(
                content="",
                tool_calls=[
                    ToolCall(name="search_medications", args={"name": "阿莫西林"}, id="call-search")
                ],
            ),
            "阿莫西林胶囊是处方药，需要凭医生处方购买。您可以先发起问诊或挂号让医生开方，"
            "处方审核通过后再来购药；具体用药请咨询医生或药师。",
        ],
    )

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "帮我买2盒阿莫西林"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    # 命中处方药：只查目录，不调 prepare，不出预览卡
    assert http_calls == [("/api/agent/medications", {"name": "阿莫西林"})]
    assert [event["event"] for event in events] == [
        "meta",
        "tool_start",
        "tool_end",
        "medications",
        "token",
        "message",
        "done",
    ]
    assert "处方药" in events[-2]["data"]["content"]


def test_symptom_only_message_never_triggers_drug_tools() -> None:
    """票 88：用户只描述症状（未点名药品）时走通用药品知识解释，绝不荐药——
    不调 search_medications / prepare_drug_order，不触达任何购药回调。
    """
    http_calls: list[tuple[str, dict[str, str]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, dict(request.url.params)))
        return httpx.Response(200, json={})

    runner, callback = _build_runner(
        handler,
        [
            "头痛时可以先休息、补充水分观察一下。我不能为您推荐具体药品，"
            "如果症状持续或加重，建议咨询医生或药师。"
        ],
    )

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(
                client, {"messages": [{"role": "user", "content": "我最近老是头痛，吃点什么药好"}]}
            )
    finally:
        asyncio.run(callback.aclose())

    # 症状描述不触发任何购药回调，无卡片事件
    assert http_calls == []
    assert [event["event"] for event in events] == ["meta", "token", "message", "done"]
    assert events[-2]["data"]["disclaimer"] == _DISCLAIMER


def test_zero_approved_prescriptions_suppresses_card_and_prompts_guidance() -> None:
    """票 80：list_approved_prescriptions 返回空列表时抑制 prescriptions 卡片不下发，
    让模型按提示词文字引导「暂无已审核处方，可先发起问诊或挂号让医生开方」。

    事件序列不含 prescriptions 卡，但仍含 tool_start/tool_end（查询本身成功，只是无数据）。
    """

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"prescriptions": []})

    runner, callback = _build_runner(
        handler,
        [
            AIMessage(
                content="",
                tool_calls=[
                    ToolCall(name="list_approved_prescriptions", args={}, id="call-list-empty")
                ],
            ),
            "您暂无已审核处方，可先发起问诊或挂号让医生开方。",
        ],
    )

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

    runner, callback = _build_runner(
        handler,
        [
            AIMessage(
                content="",
                tool_calls=[
                    ToolCall(name="list_approved_prescriptions", args={}, id="call-list-multi")
                ],
            ),
            "您有多张已审核处方，请选择一张继续购药。",
        ],
    )

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


def test_single_approved_prescription_skips_selection_card_and_prepares_preview() -> None:
    """票 80/88：list_approved_prescriptions 返回单张时按 spec「单张直通」不投影选择卡，
    模型按提示词直接调 prepare_drug_order(prescription_id=该处方 id) 装配购药预览卡。
    """
    http_calls: list[tuple[str, dict[str, str]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, dict(request.url.params)))
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
        # prepare 调用返回票 88 新形状（锁定院区药房 + 实时价格库存）
        return httpx.Response(200, json=_rx_prepare_payload())

    runner, callback = _build_runner(
        handler,
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
            "已按您的处方准备好购药预览。",
        ],
    )

    try:
        client, _ = _build_app(runner)
        with client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": "按处方买药"}]})
    finally:
        asyncio.run(callback.aclose())

    # 单张处方：先查处方（无选择卡），再直接 prepare 装配预览卡（drug_order_prepare）
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
    assert http_calls[1] == (
        "/api/agent/drug-orders/prepare",
        {"patient_id": "12", "prescription_id": "5"},
    )
    card = events[5]["data"]
    assert set(card) == _RX_CARD_KEYS
    assert card["source"] == "PRESCRIPTION"
    assert card["pharmacy_name"] == "滨江院区药房"


def test_prescription_id_in_request_drives_prepare_drug_order() -> None:
    """票 80/88：请求携带 prescription_id 时注入 AgentContext.selected_prescription_id，
    经 SystemMessage 提示模型直接调 prepare_drug_order(prescription_id=<该值>) 装配预览卡，
    不再调 list_approved_prescriptions。事件序列含 drug_order_prepare 预览卡。
    """
    http_calls: list[tuple[str, dict[str, str]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        http_calls.append((request.url.path, dict(request.url.params)))
        return httpx.Response(
            200,
            json=_rx_prepare_payload(
                prescription_id=7, doctor_name="陈志远", prescription_date="2026-08-02"
            ),
        )

    runner, callback = _build_runner(
        handler,
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
            "已按选定的处方为您准备好购药预览。",
        ],
    )

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

    # 直接装配预览卡：只调用 prepare，未查 list
    assert http_calls == [
        ("/api/agent/drug-orders/prepare", {"patient_id": "12", "prescription_id": "7"})
    ]
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
    assert set(card) == _RX_CARD_KEYS
    assert card["source"] == "PRESCRIPTION"
    assert card["prescription_id"] == 7
    assert card["doctor_name"] == "陈志远"
    assert card["pharmacy_name"] == "滨江院区药房"

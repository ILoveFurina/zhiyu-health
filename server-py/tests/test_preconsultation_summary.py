"""预问诊摘要在 done 后异步生成、回调和降级。"""

import asyncio
import logging

import httpx
import pytest
from conftest import (
    FakeAgentRunner,
    FakeEmotionJudge,
    FakePreconsultJudge,
    FakeSummaryCallback,
)

from app.schemas.preconsult import PreconsultationSummary
from app.services.chat import AgentChatService
from app.services.directory import CallbackDepartmentDirectory
from app.tools.callback import BusinessCallbackClient

_DEPARTMENTS = [
    {"id": 5, "name": "皮肤科", "category": "皮肤"},
    {"id": 8, "name": "呼吸内科", "category": "内科"},
]


def _build_harness_service(
    *,
    preconsult: FakePreconsultJudge | None = None,
    summary_callback: FakeSummaryCallback | None = None,
) -> AgentChatService:
    """直连 stream() 用的 AgentChatService 装配（不经 HTTP，确定性 await 后台 task）。"""
    fake_agent = FakeAgentRunner()
    fake_preconsult = preconsult or FakePreconsultJudge()
    return AgentChatService(
        fake_agent,
        rag_available=False,
        graph_available=False,
        emotion_judge=FakeEmotionJudge(),
        preconsult_judge=fake_preconsult,
        summary_callback=summary_callback,
    )


async def _stream_events(
    service: AgentChatService,
    *,
    scenario: str = "preconsultation",
    draft_id: int | None = 99,
    messages: list[dict] | None = None,
) -> list[dict]:
    """直接消费 AgentChatService.stream（不经 HTTP），确定性 await 后台摘要 task。

    HTTP TestClient 的 portal 事件循环无法在同步测试中可靠 pump 后台 create_task；
    直连 stream() 在 asyncio.run 内消费全部事件后 await _last_summary_task，
    确保摘要回调断言确定执行完毕。
    """
    events: list[dict] = []
    async for event in service.stream(
        messages=messages or [{"role": "user", "content": "我咳嗽三天了，青霉素过敏"}],
        patient_id=12,
        conversation_id=7,
        effort_choice="auto",
        scenario=scenario,
        preconsultation_draft_id=draft_id,
    ):
        events.append(event)
    # 摘要后台 task 在 done 之后创建，await 确保回调执行完毕再断言
    if service._last_summary_task is not None:
        await service._last_summary_task
    return events


def test_summary_async_callback_after_done() -> None:
    """摘要异步化：message 事件不含摘要字段；后台 task 算出快照后回调 server-java 落草稿。

    摘要不再阻塞 message/done--回调在 done 之后异步执行，payload 含摘要字段 +
    建议科室 + 免责声明（与原 message 挂载的快照结构一致，server-java 复用 applySummary）。
    """
    summary = PreconsultationSummary(
        chief_complaint="咳嗽三天",
        present_illness="三天前受凉后干咳，无发热，夜间加重",
        allergy_history="青霉素",
        suggested_standard_department_id=8,
    )
    callback = FakeSummaryCallback()
    service = _build_harness_service(
        preconsult=FakePreconsultJudge([summary]), summary_callback=callback
    )
    events = asyncio.run(_stream_events(service, draft_id=99))

    # 流完整到达 done，message 不再携带 preconsultation_summary（异步化）
    assert [e["event"] for e in events] == [
        "meta",
        "knowledge",
        "token",
        "token",
        "token",
        "message",
        "done",
    ]
    final = events[-2]
    assert final["event"] == "message"
    assert final["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    assert "preconsultation_summary" not in final["data"]

    # 后台 task 已完成：回调被调用一次，draftId 与 payload 正确
    assert len(callback.calls) == 1
    assert callback.calls[0]["draft_id"] == 99
    snapshot = callback.calls[0]["payload"]
    assert snapshot["chief_complaint"] == "咳嗽三天"
    assert snapshot["present_illness"] == "三天前受凉后干咳，无发热，夜间加重"
    assert snapshot["allergy_history"] == "青霉素"
    assert snapshot["suggested_standard_department_id"] == 8
    # 摘要属 AI 产出：快照内携带免责声明标注（契约 _summary_event_field_doc）
    assert snapshot["disclaimer"] == "仅供参考，不替代医生诊断"


def test_summary_judge_none_omits_callback_and_stream_completes() -> None:
    """判定器返回 None：不触发回调，流完整到达 done，token 不受影响。"""
    callback = FakeSummaryCallback()
    service = _build_harness_service(
        preconsult=FakePreconsultJudge([None]), summary_callback=callback
    )
    events = asyncio.run(
        _stream_events(service, messages=[{"role": "user", "content": "我咳嗽三天了"}])
    )

    assert [e["event"] for e in events] == [
        # knowledge 为 rag 降级元事件（契约默认 rag，测试装配检索器不可用）
        "meta",
        "knowledge",
        "token",
        "token",
        "token",
        "message",
        "done",
    ]
    assert events[1]["data"] == {"source": "rag", "status": "degraded", "count": 0}
    final = events[-2]
    assert "preconsultation_summary" not in final["data"]
    assert final["data"]["content"] == "你好，我是小愈。"
    assert final["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    # None 快照不触发回调
    assert callback.calls == []


def test_summary_judge_exception_degrades_without_breaking_stream() -> None:
    """判定器异常：编排层降级不回调，不得掐断 SSE 流。"""
    callback = FakeSummaryCallback()
    service = _build_harness_service(
        preconsult=FakePreconsultJudge(raises=True), summary_callback=callback
    )
    events = asyncio.run(
        _stream_events(service, messages=[{"role": "user", "content": "我咳嗽三天了"}])
    )

    assert [e["event"] for e in events] == [
        "meta",
        "knowledge",
        "token",
        "token",
        "token",
        "message",
        "done",
    ]
    assert "preconsultation_summary" not in events[-2]["data"]
    # 异常被吞，不触发回调
    assert callback.calls == []


def test_summary_judge_receives_controlled_department_candidates() -> None:
    """受控标准科室解析：候选目录经 server-java 回调拉取并传给判定器。

    预问诊跳过强制号源查询：目录回调只发生一次（摘要判定），不触达号源端点。
    摘要异步化后判定在 done 之后后台执行，故直连 stream() + await task 确定性断言。
    """
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/api/agent/standard-departments":
            return httpx.Response(200, json={"departments": _DEPARTMENTS})
        return httpx.Response(404, json={"detail": "not found"})

    callback = BusinessCallbackClient(
        "http://server-java.test",
        transport=httpx.MockTransport(handler),
        callback_secret="shared-secret",
    )
    fake_preconsult = FakePreconsultJudge()
    service = AgentChatService(
        FakeAgentRunner(),
        rag_available=False,
        graph_available=False,
        emotion_judge=FakeEmotionJudge(),
        preconsult_judge=fake_preconsult,
        directory=CallbackDepartmentDirectory(callback),
    )

    async def run() -> None:
        events = await _stream_events(
            service,
            messages=[{"role": "user", "content": "我咳嗽三天了"}],
            draft_id=99,
        )
        assert events[-1]["event"] == "done"

    try:
        asyncio.run(run())
    finally:
        asyncio.run(callback.aclose())

    # 判定器收到受控候选目录（id+name 来自平台目录，非 LLM 编造）
    assert fake_preconsult.calls[0]["candidates"] == _DEPARTMENTS
    # 只拉目录，不查号源（预问诊不进挂号闭环）
    assert [r.url.path for r in requests] == ["/api/agent/standard-departments"]


def test_summary_callback_failure_logs_only_exception_type(
    caplog: pytest.LogCaptureFixture,
) -> None:
    class SensitiveFailureCallback:
        async def apply(self, draft_id: int, payload: dict) -> None:
            raise RuntimeError("患者咳嗽三天，callback response body")

    summary = PreconsultationSummary(
        chief_complaint="咳嗽三天",
        present_illness="夜间加重",
        allergy_history="",
        suggested_standard_department_id=8,
    )
    service = _build_harness_service(
        preconsult=FakePreconsultJudge([summary]),
        summary_callback=SensitiveFailureCallback(),
    )
    caplog.set_level(logging.WARNING, logger="app.services.chat_preconsultation")

    asyncio.run(_stream_events(service))

    assert "error=RuntimeError" in caplog.text
    assert "患者咳嗽三天" not in caplog.text
    assert "callback response body" not in caplog.text

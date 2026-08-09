"""票 55 预问诊场景（preconsultation）测试：HTTP seam + fake 替换 LLM/摘要判定器。

覆盖：
- 场景被 AgentChatRequest 接受（契约 scenarios），非法场景仍 422（回归）
- 工具隔离：预问诊不暴露 recommend_doctors/get_doctor_slots/create_appointment/
  get_appointment 业务工具；知识工具仍按 knowledge_source（契约默认 rag）注入；
  triage 场景工具集保持不变（回归）
- 专用提示词：预问诊使用 PRECONSULTATION_SYSTEM_PROMPT，triage 保持原 SYSTEM_PROMPT
- 摘要异步化（票 55 改造）：摘要不再阻塞 message 事件，改为 done 之后的后台 task
  异步整理并回调 server-java 落草稿；message 事件不含 preconsultation_summary 字段；
  判定失败/异常省略回调，流不受影响
- StructuredPreconsultJudge：json_object + pydantic 校验 + 2 次重试 + 目录外
  科室 ID 归一化 None + 失败降级 None（复用 test_emotion.py 的 fake 范式）
"""

import asyncio
import json
from collections.abc import Iterator
from typing import Any

import httpx
from conftest import (
    TEST_AGENT_SECRET,
    FakeAgentRunner,
    FakeEmotionJudge,
    FakeKnowledgeRetriever,
    FakePreconsultJudge,
    FakeSummaryCallback,
    StubHealthService,
)
from fastapi.testclient import TestClient
from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatResult

from app.agent.preconsult import StructuredPreconsultJudge
from app.agent.runner import AgentContext, LangGraphAgentRunner
from app.testing import create_test_app
from app.schemas.preconsult import PreconsultationSummary
from app.services.chat import AgentChatService
from app.services.directory import CallbackDepartmentDirectory
from app.tools.business import build_business_tools
from app.tools.callback import BusinessCallbackClient

_DEPARTMENTS = [
    {"id": 5, "name": "皮肤科", "category": "皮肤"},
    {"id": 8, "name": "呼吸内科", "category": "内科"},
]


def _post_chat(client: TestClient, payload: dict) -> list[dict]:
    # 与 test_chat_api 不同：默认不显式关知识增强，以验证契约场景默认（preconsultation=rag）
    payload = {"patient_id": 12, "conversation_id": 7, **payload}
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


def _build_harness_app(
    *,
    preconsult: FakePreconsultJudge | None = None,
    summary_callback: FakeSummaryCallback | None = None,
) -> tuple[TestClient, FakeAgentRunner, FakePreconsultJudge]:
    """FakeAgentRunner + fake 摘要判定器的轻量装配（不触真实目录与 LLM）。"""
    fake_agent = FakeAgentRunner()
    fake_preconsult = preconsult or FakePreconsultJudge()
    app = create_test_app(
        health_service=StubHealthService(),
        agent_runner=fake_agent,
        agent_auth_secret=TEST_AGENT_SECRET,
        emotion_judge=FakeEmotionJudge(),
        preconsult_judge=fake_preconsult,
        summary_callback=summary_callback,
    )
    return TestClient(app), fake_agent, fake_preconsult


async def _stream_events(
    service: AgentChatService, *, scenario: str = "preconsultation",
    draft_id: int | None = 99, messages: list[dict] | None = None,
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


def test_preconsultation_scenario_accepted_and_streams_with_locked_profile() -> None:
    """preconsultation 场景被接受：正常流式输出，自动档映射 disabled，锁定档案到达 runner。"""
    client, fake_agent, fake_preconsult = _build_harness_app()
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "我咳嗽三天了，想在线问医生"}],
            "scenario": "preconsultation",
            "health_profile": {
                "id": 31,
                "display_name": "妈妈",
                "gender": "女",
                "birth_date": "1962-05-08",
                "relationship": "母亲",
                "allergies": ["青霉素"],
            },
        })

    kinds = [e["event"] for e in events]
    assert kinds[0] == "meta"
    assert kinds[-1] == "done"
    assert kinds.count("token") == 3
    final = events[-2]
    assert final["event"] == "message"
    assert final["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    # 自动档预问诊映射 disabled（对话型，速度优先），不外传 auto
    assert fake_agent.calls[0]["effort"] == "disabled"
    # 场景与锁定健康档案进入 runner 可信上下文
    context = fake_agent.calls[0]["context"]
    assert context.scenario == "preconsultation"
    assert context.health_profile.display_name == "妈妈"
    assert context.health_profile.allergies == ["青霉素"]
    # 摘要判定器在 done 之后后台异步调用（非 message 同步路径）；HTTP 流结束时
    # 后台 task 可能尚未执行，其调用断言移至 test_summary_async_callback_after_done。
    assert fake_preconsult.calls == []


def test_invalid_scenario_still_rejected() -> None:
    """场景白名单回归：契约外场景值仍 422（预问诊权限不得由客户端自由指定）。"""
    client, _, _ = _build_harness_app()
    with client:
        response = client.post(
            "/api/agent/chat",
            json={
                "messages": [{"role": "user", "content": "你好"}],
                "patient_id": 12,
                "conversation_id": 7,
                "scenario": "free_consult",
            },
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 422


def _tool_name_recording_runner(
    callback: BusinessCallbackClient, bound: list[list[str]]
) -> LangGraphAgentRunner:
    """真实 LangGraph runner + 记录 bind_tools 工具名的 fake 模型。"""

    class ToolNameRecordingFake(GenericFakeChatModel):
        messages: Iterator[AIMessage | str]

        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            bound.append([tool.name for tool in tools])
            return self

    fake = ToolNameRecordingFake(disable_streaming=True, messages=iter(["好的，请继续。"]))
    return LangGraphAgentRunner(
        lambda effort: fake,
        tools=build_business_tools(callback),
        knowledge_retriever=FakeKnowledgeRetriever(),
    )


_BUSINESS_TOOLS = {
    "recommend_doctors",
    "get_doctor_slots",
    "create_appointment",
    "get_appointment",
    "search_medications",
    "list_approved_prescriptions",
    "prepare_drug_order",
}


def _build_tool_isolation_app(runner: LangGraphAgentRunner) -> TestClient:
    app = create_test_app(
        health_service=StubHealthService(),
        agent_runner=runner,
        agent_auth_secret=TEST_AGENT_SECRET,
        emotion_judge=FakeEmotionJudge(),
        preconsult_judge=FakePreconsultJudge(),
        rag_available=True,
    )
    return TestClient(app)


def test_preconsultation_isolates_business_tools_but_keeps_knowledge_tool() -> None:
    """工具隔离：预问诊图谱不含四个业务工具（编排代码隔离，非提示词），rag 知识工具保留。"""
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(500)  # 任何业务回调都算泄漏；预问诊不得触达

    callback = BusinessCallbackClient(
        "http://server-java.test",
        transport=httpx.MockTransport(handler),
        callback_secret="shared-secret",
    )
    bound: list[list[str]] = []
    runner = _tool_name_recording_runner(callback, bound)

    try:
        client = _build_tool_isolation_app(runner)
        with client:
            events = _post_chat(client, {
                "messages": [{"role": "user", "content": "我身上起疹子两天了"}],
                "scenario": "preconsultation",
            })
    finally:
        asyncio.run(callback.aclose())

    assert [e["event"] for e in events] == ["meta", "token", "message", "done"]
    assert len(bound) == 1  # (effort, knowledge_source, scenario) 维度的独立编译图
    assert not _BUSINESS_TOOLS & set(bound[0])  # 四个业务工具一个都不在
    assert bound[0] == ["search_knowledge"]  # 契约默认 rag：知识工具仍注入
    assert requests == []  # 业务回调零触达


def test_triage_scenario_keeps_business_tools_regression() -> None:
    """triage 回归：工具集保持业务工具 + 知识工具，不受预问诊隔离影响。"""
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(500)

    callback = BusinessCallbackClient(
        "http://server-java.test",
        transport=httpx.MockTransport(handler),
        callback_secret="shared-secret",
    )
    bound: list[list[str]] = []
    runner = _tool_name_recording_runner(callback, bound)

    try:
        client = _build_tool_isolation_app(runner)
        with client:
            _post_chat(client, {
                "messages": [{"role": "user", "content": "我咳嗽怎么办"}],
                "scenario": "triage",
            })
    finally:
        asyncio.run(callback.aclose())

    assert len(bound) == 1
    assert _BUSINESS_TOOLS <= set(bound[0])
    assert "search_knowledge" in bound[0]


def test_preconsultation_uses_dedicated_prompt_and_triage_keeps_original() -> None:
    """一次一问提示词：预问诊用专用提示词（无挂号能力话术），triage 保持原提示词。"""
    seen: dict[str, list[list[tuple[str, Any]]]] = {"preconsultation": [], "triage": []}

    def make_model(scenario: str):
        class PromptRecordingFake(GenericFakeChatModel):
            messages: Iterator[AIMessage | str]

            def _generate(
                self,
                messages: list[BaseMessage],
                stop: list[str] | None = None,
                run_manager: CallbackManagerForLLMRun | None = None,
                **kwargs: Any,
            ) -> ChatResult:
                seen[scenario].append([(m.type, m.content) for m in messages])
                return super()._generate(messages, stop, run_manager, **kwargs)

        return PromptRecordingFake(disable_streaming=True, messages=iter(["好的"]))

    async def run(scenario: str) -> None:
        model = make_model(scenario)
        runner = LangGraphAgentRunner(lambda effort: model)
        context = AgentContext(patient_id=12, conversation_id=7, scenario=scenario)
        async for _ in runner.astream_reply(
            [{"role": "user", "content": "我咳嗽三天了"}], "disabled", context
        ):
            pass

    asyncio.run(run("preconsultation"))
    asyncio.run(run("triage"))

    preconsult_prompt = seen["preconsultation"][0][0]
    assert preconsult_prompt[0] == "system"
    assert "预问诊" in preconsult_prompt[1]
    assert "一次只追问一个最关键的问题" in preconsult_prompt[1]
    assert "主诉" in preconsult_prompt[1] and "过敏史" in preconsult_prompt[1]
    # 不承载挂号闭环能力话术（工具已隔离，提示词也不再提及这些能力）
    assert "recommend_doctors" not in preconsult_prompt[1]
    assert "create_appointment" not in preconsult_prompt[1]

    triage_prompt = seen["triage"][0][0]
    assert triage_prompt[0] == "system"
    assert "recommend_doctors" in triage_prompt[1]
    assert "预问诊阶段" not in triage_prompt[1]


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
    service: AgentChatService, *, scenario: str = "preconsultation",
    draft_id: int | None = 99, messages: list[dict] | None = None,
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
        "meta", "knowledge", "token", "token", "token", "message", "done",
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
    events = asyncio.run(_stream_events(service, messages=[{"role": "user", "content": "我咳嗽三天了"}]))

    assert [e["event"] for e in events] == [
        # knowledge 为 rag 降级元事件（契约默认 rag，测试装配检索器不可用）
        "meta", "knowledge", "token", "token", "token", "message", "done",
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
    events = asyncio.run(_stream_events(service, messages=[{"role": "user", "content": "我咳嗽三天了"}]))

    assert [e["event"] for e in events] == [
        "meta", "knowledge", "token", "token", "token", "message", "done",
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


class FakeRawPreconsultModel:
    """记录调用并按预设序列返回原始文本（可模拟非法 JSON / 校验失败 / 异常）。"""

    def __init__(self, responses: list[str | Exception]) -> None:
        self.responses = iter(responses)
        self.calls: list[str] = []

    async def ainvoke(self, prompt_text: str) -> str:
        self.calls.append(prompt_text)
        response = next(self.responses)
        if isinstance(response, Exception):
            raise response
        return response


_MESSAGES = [{"role": "user", "content": "我咳嗽三天了，青霉素过敏"}]


def test_judge_returns_summary_with_in_catalog_department() -> None:
    model = FakeRawPreconsultModel([json.dumps({
        "chief_complaint": "咳嗽三天",
        "present_illness": "干咳无痰",
        "allergy_history": "青霉素",
        "suggested_standard_department_id": 8,
    })])
    judge = StructuredPreconsultJudge(model)

    result = asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS))

    assert result is not None
    assert result.chief_complaint == "咳嗽三天"
    assert result.suggested_standard_department_id == 8
    # prompt 携带候选目录与对话历史（受控解析的输入）
    assert "呼吸内科" in model.calls[0]
    assert "我咳嗽三天了，青霉素过敏" in model.calls[0]


def test_judge_normalizes_out_of_catalog_department_to_none() -> None:
    """目录外/臆造科室 ID：归一化为 None，摘要文本字段保留。"""
    model = FakeRawPreconsultModel([json.dumps({
        "chief_complaint": "咳嗽三天",
        "present_illness": "干咳无痰",
        "allergy_history": "",
        "suggested_standard_department_id": 999,
    })])
    judge = StructuredPreconsultJudge(model)

    result = asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS))

    assert result is not None
    assert result.suggested_standard_department_id is None
    assert result.chief_complaint == "咳嗽三天"


def test_judge_forces_none_department_when_candidates_empty() -> None:
    """目录不可用（空候选）：任何建议科室都越界，强制 None，摘要本体仍保留。"""
    model = FakeRawPreconsultModel([json.dumps({
        "chief_complaint": "咳嗽三天",
        "present_illness": "干咳无痰",
        "allergy_history": "",
        "suggested_standard_department_id": 8,
    })])
    judge = StructuredPreconsultJudge(model)

    result = asyncio.run(judge.judge(_MESSAGES, []))

    assert result is not None
    assert result.suggested_standard_department_id is None


def test_judge_retries_on_invalid_json_then_succeeds() -> None:
    model = FakeRawPreconsultModel([
        "这不是 JSON",
        json.dumps({
            "chief_complaint": "咳嗽三天",
            "present_illness": "干咳无痰",
            "allergy_history": "",
            "suggested_standard_department_id": None,
        }),
    ])
    judge = StructuredPreconsultJudge(model)

    result = asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS))

    assert result is not None
    assert result.chief_complaint == "咳嗽三天"
    assert len(model.calls) == 2


def test_judge_returns_none_after_two_validation_failures() -> None:
    model = FakeRawPreconsultModel([
        json.dumps({"chief_complaint": "咳嗽三天"}),  # 缺字段：校验失败
        "仍然不是 JSON",
    ])
    judge = StructuredPreconsultJudge(model)

    assert asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS)) is None
    assert len(model.calls) == 2


def test_judge_returns_none_on_model_exception() -> None:
    model = FakeRawPreconsultModel([RuntimeError("方舟调用超时")])
    judge = StructuredPreconsultJudge(model)

    assert asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS)) is None


def test_judge_rejects_extra_fields_in_llm_output() -> None:
    """extra=forbid：LLM 输出多字段两次校验失败后降级 None。"""
    payload = json.dumps({
        "chief_complaint": "咳嗽三天",
        "present_illness": "干咳无痰",
        "allergy_history": "",
        "suggested_standard_department_id": None,
        "diagnosis": "上呼吸道感染",  # 越权字段：摘要判定器不做诊断
    })
    model = FakeRawPreconsultModel([payload, payload])
    judge = StructuredPreconsultJudge(model)

    assert asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS)) is None
    assert len(model.calls) == 2

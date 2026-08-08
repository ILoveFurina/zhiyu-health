"""票 50 智能导诊强制号源查询（HTTP seam，MockTransport 替换 server-java）。

覆盖：明确科室直查、多轮 resolved 收敛直查（票 62）、resolved 同科室已出卡
去重（票 62）、ambiguous 退回 Agent 流、摘要先于卡片、查询失败出 failed 卡、
retry 字段跳过解析直查、全部无号仍出 ok 卡（empty 摘要）、目录不可用退回
Agent 流，以及回调 URL/鉴权头断言。
"""

import asyncio
import json
from types import SimpleNamespace
from typing import Any

import httpx
from conftest import TEST_AGENT_SECRET, FakeAgentRunner, FakeEmotionJudge, FakeTriageJudge, StubHealthService
from fastapi.testclient import TestClient

from app.testing import create_test_app
from app.schemas.triage import TriageResolution
from app.services.directory import CallbackDepartmentDirectory
from app.tools.callback import BusinessCallbackClient

_DEPARTMENTS = [
    {"id": 5, "name": "皮肤科", "category": "皮肤"},
    {"id": 8, "name": "呼吸内科", "category": "内科"},
]


def _slots_payload(*, bookable: bool = True) -> dict[str, Any]:
    doctors = [
        {
            "doctor_id": 21,
            "doctor_name": "周安宁",
            "title": "副主任医师",
            "specialty": "白癜风、银屑病",
            "registration_fee": 25.0,
            "hospital_id": 1,
            "hospital_name": "智愈市人民医院",
            "campus_id": 1,
            "campus_name": "总院",
            "campus_address": "智愈市安康路 88 号",
            "distance_km": 3.2,
            "bookable": bookable,
            "earliest_bookable": {"date": "2026-08-09", "time_slot": "PM"} if bookable else None,
            "slots": [
                {"schedule_id": 91, "schedule_date": "2026-08-09", "time_slot": "PM",
                 "remaining_slots": 3 if bookable else 0},
            ],
        },
        {
            "doctor_id": 22,
            "doctor_name": "林知远",
            "title": "主治医师",
            "specialty": "湿疹、荨麻疹、痤疮",
            "registration_fee": 15.0,
            "hospital_id": 2,
            "hospital_name": "智愈市中医院",
            "campus_id": 2,
            "campus_name": "东院区",
            "campus_address": "智愈市康宁路 6 号",
            "distance_km": 5.1,
            "bookable": bookable,
            "earliest_bookable": {"date": "2026-08-08", "time_slot": "AM"} if bookable else None,
            "slots": [
                {"schedule_id": 92, "schedule_date": "2026-08-08", "time_slot": "AM",
                 "remaining_slots": 2 if bookable else 0},
            ],
        },
    ]
    return {
        "standard_department": {"id": 5, "name": "皮肤科", "category": "皮肤"},
        "days": [f"2026-08-{day:02d}" for day in range(8, 22)],
        "doctors": doctors,
    }


def _directory_handler(
    requests: list[httpx.Request],
    *,
    departments_status: int = 200,
    slots_status: int = 200,
    slots_payload: dict[str, Any] | None = None,
    bare_list: bool = False,
):
    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/api/agent/standard-departments":
            if departments_status != 200:
                return httpx.Response(departments_status, json={"detail": "目录暂不可用"})
            body = _DEPARTMENTS if bare_list else {"departments": _DEPARTMENTS}
            return httpx.Response(200, json=body)
        if request.url.path == "/api/agent/standard-departments/5/slots":
            if slots_status != 200:
                return httpx.Response(slots_status, json={"detail": "号源查询暂不可用"})
            return httpx.Response(200, json=slots_payload or _slots_payload())
        return httpx.Response(404, json={"detail": "not found"})

    return handler


def _build_app(
    handler,
    *,
    triage_results: list[TriageResolution] | None = None,
) -> SimpleNamespace:
    fake_agent = FakeAgentRunner()
    fake_emotion = FakeEmotionJudge()
    fake_triage = FakeTriageJudge(triage_results)
    callback = BusinessCallbackClient(
        "http://server-java.test",
        transport=httpx.MockTransport(handler),
        callback_secret="shared-secret",
    )
    app = create_test_app(
        health_service=StubHealthService(),
        agent_runner=fake_agent,
        agent_auth_secret=TEST_AGENT_SECRET,
        emotion_judge=fake_emotion,
        triage_judge=fake_triage,
        directory=CallbackDepartmentDirectory(callback),
    )
    return SimpleNamespace(
        client=TestClient(app), agent=fake_agent, emotion=fake_emotion,
        triage=fake_triage, callback=callback,
    )


def _post_chat(client: TestClient, payload: dict) -> list[dict]:
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


def _run(harness: SimpleNamespace, payload: dict) -> list[dict]:
    try:
        with harness.client:
            return _post_chat(harness.client, payload)
    finally:
        asyncio.run(harness.callback.aclose())


def test_explicit_booking_triggers_forced_query_with_summary_before_card() -> None:
    """用户明确"科室+挂号"意图：编排层直查号源，摘要在前卡片在后，不经 Agent。"""
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests),
        triage_results=[TriageResolution(
            status="explicit_booking", standard_department_id=5, rationale="明确皮肤科挂号"
        )],
    )

    events = _run(harness, {
        "messages": [{"role": "user", "content": "我要挂皮肤科的号"}],
        "longitude": 121.4737,
        "latitude": 31.2304,
    })

    # 事件顺序钉死：meta -> message 摘要 -> department_slots 卡 -> done
    assert [e["event"] for e in events] == ["meta", "message", "department_slots", "done"]
    # Agent 流未被调用（强制查询短路）
    assert harness.agent.calls == []
    # 摘要由代码按 ok 模板拼装：科室 + 最早可约（所有 bookable 医生最小值）+ 有号医生数
    # 票 60：末尾拼接最早可约医生（林知远）的推荐子句（recommendation 模板）
    summary = events[1]["data"]
    assert summary["role"] == "assistant"
    assert summary["content"] == (
        "已为您查询皮肤科号源：最早可约2026-08-08 上午，当前有号医生2位。"
        "推荐林知远（主治医师），擅长湿疹、荨麻疹、痤疮。"
    )
    assert summary["disclaimer"] == "仅供参考，不替代医生诊断"
    # 卡片携带 server-java 返回全体字段 + status:ok + 免责声明
    card = events[2]["data"]
    assert card["status"] == "ok"
    assert card["standard_department"] == {"id": 5, "name": "皮肤科", "category": "皮肤"}
    assert len(card["days"]) == 14
    assert card["doctors"][1]["campus_address"] == "智愈市康宁路 6 号"
    assert card["disclaimer"] == "仅供参考，不替代医生诊断"
    # 科室解析收到完整对话与候选目录
    assert len(harness.triage.calls) == 1
    assert harness.triage.calls[0]["candidates"] == _DEPARTMENTS
    # 回调 URL 与鉴权头：先拉目录再查号源，坐标作为查询参数透传
    assert [r.url.path for r in requests] == [
        "/api/agent/standard-departments",
        "/api/agent/standard-departments/5/slots",
    ]
    for request in requests:
        assert request.headers["X-Agent-Callback-Token"] == "shared-secret"
        assert request.url.params["longitude"] == "121.4737"
        assert request.url.params["latitude"] == "31.2304"


def test_earliest_doctor_without_specialty_omits_recommendation_clause() -> None:
    """票 60：最早可约医生无 specialty 字段时整句省略推荐子句，摘要保持旧全文。"""
    payload = _slots_payload()
    for doctor in payload["doctors"]:
        doctor.pop("specialty", None)
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests, slots_payload=payload),
        triage_results=[TriageResolution(
            status="explicit_booking", standard_department_id=5, rationale="明确皮肤科挂号"
        )],
    )

    events = _run(harness, {"messages": [{"role": "user", "content": "我要挂皮肤科的号"}]})

    assert [e["event"] for e in events] == ["meta", "message", "department_slots", "done"]
    assert events[1]["data"]["content"] == "已为您查询皮肤科号源：最早可约2026-08-08 上午，当前有号医生2位。"


def test_earliest_tie_breaks_by_doctor_id() -> None:
    """票 60：同一时间点多医生可约，推荐子句取 doctor_id 最小者（与列表顺序无关）。"""
    payload = _slots_payload()
    # 把 doctor_id 较大的林知远挪到列表首位并拉到同一最早时间点，
    # 若按列表顺序取 min 会误推荐林知远，tie-break 必须推荐周安宁
    doctors = payload["doctors"]
    doctors.reverse()
    for doctor in doctors:
        doctor["earliest_bookable"] = {"date": "2026-08-08", "time_slot": "AM"}
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests, slots_payload=payload),
        triage_results=[TriageResolution(
            status="explicit_booking", standard_department_id=5, rationale="明确皮肤科挂号"
        )],
    )

    events = _run(harness, {"messages": [{"role": "user", "content": "我要挂皮肤科的号"}]})

    assert events[1]["data"]["content"] == (
        "已为您查询皮肤科号源：最早可约2026-08-08 上午，当前有号医生2位。"
        "推荐周安宁（副主任医师），擅长白癜风、银屑病。"
    )


def test_multi_turn_resolved_triggers_forced_query() -> None:
    """票 62：多轮导诊收敛到单一明确科室（resolved）即直查出卡，
    不再等用户明说挂号（摘要 + 卡片单步完成，不经 Agent 流）。"""
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests),
        triage_results=[TriageResolution(
            status="resolved", standard_department_id=5, rationale="症状收敛至皮肤科"
        )],
    )

    events = _run(harness, {
        "messages": [
            {"role": "user", "content": "身上起了一片红疹"},
            {"role": "assistant", "content": "疹子痒不痒？有没有发烧？"},
            {"role": "user", "content": "很痒，没有发烧"},
        ],
    })

    assert [e["event"] for e in events] == ["meta", "message", "department_slots", "done"]
    assert harness.agent.calls == []
    assert [r.url.path for r in requests] == [
        "/api/agent/standard-departments",
        "/api/agent/standard-departments/5/slots",
    ]


def test_resolved_same_department_already_summarized_skips_duplicate_query() -> None:
    """票 62 去重守卫：resolved 命中的科室上轮已出号源摘要（ok 或 empty 形态），
    不重复直查，退回正常 Agent 流——收敛后的闲聊不重复推卡。"""
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests),
        triage_results=[TriageResolution(
            status="resolved", standard_department_id=5, rationale="对话仍收敛至皮肤科"
        )],
    )

    events = _run(harness, {
        "messages": [
            {"role": "user", "content": "身上起了一片红疹"},
            {"role": "assistant", "content": "已为您查询皮肤科号源：最早可约2026-08-08 上午，当前有号医生2位。"},
            {"role": "user", "content": "好的，谢谢"},
        ],
    })

    assert [e["event"] for e in events] == ["meta", "token", "token", "token", "message", "done"]
    assert len(harness.agent.calls) == 1
    # 只拉了候选目录，未重复触发号源回调
    assert [r.url.path for r in requests] == ["/api/agent/standard-departments"]


def test_explicit_booking_requeries_even_after_summary() -> None:
    """票 62：用户出卡后再次明确表达挂号意图（explicit_booking）不受去重限制，仍直查。"""
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests),
        triage_results=[TriageResolution(
            status="explicit_booking", standard_department_id=5, rationale="再次明确挂号"
        )],
    )

    events = _run(harness, {
        "messages": [
            {"role": "user", "content": "身上起了一片红疹"},
            {"role": "assistant", "content": "已为您查询皮肤科号源：最早可约2026-08-08 上午，当前有号医生2位。"},
            {"role": "user", "content": "再帮我查一下皮肤科的号"},
        ],
    })

    assert [e["event"] for e in events] == ["meta", "message", "department_slots", "done"]
    assert harness.agent.calls == []


def test_ambiguous_falls_back_to_agent_flow_without_slots_callback() -> None:
    """仍有多个可能科室（ambiguous）但 judge 未给候选：走正常 Agent 流，
    不查号源、不出选择卡（候选为空时选择卡没有可点项，只留文字追问）。"""
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests),
        triage_results=[TriageResolution(status="ambiguous", rationale="皮肤科或变态反应科")],
    )

    events = _run(harness, {"messages": [{"role": "user", "content": "身上痒怎么办"}]})

    assert [e["event"] for e in events] == ["meta", "token", "token", "token", "message", "done"]
    assert len(harness.agent.calls) == 1
    # 只拉了候选目录，未触发号源回调
    assert [r.url.path for r in requests] == ["/api/agent/standard-departments"]


def test_ambiguous_with_candidates_yields_options_card_after_message() -> None:
    """票 65：ambiguous 且 judge 产出候选科室——Agent 文字流照常，message 事件后
    追加 department_options 选择卡（id 来自 judge、name 按 id 从目录确定性查出），
    不查号源、不短路 Agent 流。"""
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests),
        triage_results=[TriageResolution(
            status="ambiguous",
            candidate_department_ids=[8, 5],
            rationale="呼吸内科或皮肤科",
        )],
    )

    events = _run(harness, {"messages": [{"role": "user", "content": "我头痛发热，该挂什么科"}]})

    assert [e["event"] for e in events] == [
        "meta", "token", "token", "token", "message", "department_options", "done",
    ]
    assert len(harness.agent.calls) == 1
    card = events[5]["data"]
    assert card["standard_departments"] == [
        {"id": 8, "name": "呼吸内科"},
        {"id": 5, "name": "皮肤科"},
    ]
    assert card["disclaimer"] == "仅供参考，不替代医生诊断"
    # 只拉了候选目录，未触发号源回调
    assert [r.url.path for r in requests] == ["/api/agent/standard-departments"]


def test_slots_failure_yields_failed_card_without_message() -> None:
    """号源查询失败：出 failed 卡（失败文案 + 科室 ID），不出空白卡、不进 Agent 流。"""
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests, slots_status=500),
        triage_results=[TriageResolution(
            status="explicit_booking", standard_department_id=5, rationale="明确皮肤科挂号"
        )],
    )

    events = _run(harness, {"messages": [{"role": "user", "content": "我要挂皮肤科的号"}]})

    assert [e["event"] for e in events] == ["meta", "department_slots", "done"]
    card = events[1]["data"]
    assert card["status"] == "failed"
    assert card["standard_department"] == {"id": 5}
    assert card["message"] == "号源查询失败，请稍后重试。"
    assert card["disclaimer"] == "仅供参考，不替代医生诊断"
    assert harness.agent.calls == []


def test_retry_standard_department_id_skips_triage_and_queries_directly() -> None:
    """重试字段直查：跳过目录拉取、科室解析与 Agent 流，复用已确定科室。"""
    requests: list[httpx.Request] = []
    harness = _build_app(_directory_handler(requests))  # triage 无编排结果，被调用即露馅

    events = _run(harness, {
        "messages": [{"role": "user", "content": "重新查询号源"}],
        "retry_standard_department_id": 5,
        "longitude": 121.4737,
        "latitude": 31.2304,
    })

    assert [e["event"] for e in events] == ["meta", "message", "department_slots", "done"]
    assert harness.triage.calls == []
    assert harness.agent.calls == []
    # 只调号源端点，不再拉目录
    assert [r.url.path for r in requests] == ["/api/agent/standard-departments/5/slots"]
    assert requests[0].headers["X-Agent-Callback-Token"] == "shared-secret"


def test_all_unbookable_still_yields_ok_card_with_empty_summary() -> None:
    """全部无号：仍出 ok 卡（医生保留禁用预约），摘要为 empty 模板。"""
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests, slots_payload=_slots_payload(bookable=False)),
        triage_results=[TriageResolution(
            status="explicit_booking", standard_department_id=5, rationale="明确皮肤科挂号"
        )],
    )

    events = _run(harness, {"messages": [{"role": "user", "content": "我要挂皮肤科的号"}]})

    assert [e["event"] for e in events] == ["meta", "message", "department_slots", "done"]
    assert events[1]["data"]["content"] == "皮肤科未来14天暂无可约号源。"
    card = events[2]["data"]
    assert card["status"] == "ok"
    assert len(card["doctors"]) == 2  # 无号医生保留在卡内
    assert all(not d["bookable"] for d in card["doctors"])


def test_directory_failure_falls_back_to_agent_flow() -> None:
    """候选目录查询失败：跳过科室解析，走正常 Agent 流，不查号源。"""
    requests: list[httpx.Request] = []
    harness = _build_app(_directory_handler(requests, departments_status=500))

    events = _run(harness, {"messages": [{"role": "user", "content": "我要挂皮肤科的号"}]})

    assert [e["event"] for e in events] == ["meta", "token", "token", "token", "message", "done"]
    assert harness.triage.calls == []
    assert len(harness.agent.calls) == 1
    assert [r.url.path for r in requests] == ["/api/agent/standard-departments"]


def test_departments_bare_list_shape_is_tolerated() -> None:
    """目录端点容错裸列表返回形态（非 {departments: [...]} 包装）。"""
    requests: list[httpx.Request] = []
    harness = _build_app(
        _directory_handler(requests, bare_list=True),
        triage_results=[TriageResolution(
            status="explicit_booking", standard_department_id=5, rationale="明确皮肤科挂号"
        )],
    )

    events = _run(harness, {"messages": [{"role": "user", "content": "我要挂皮肤科的号"}]})

    assert [e["event"] for e in events] == ["meta", "message", "department_slots", "done"]
    assert harness.triage.calls[0]["candidates"] == _DEPARTMENTS

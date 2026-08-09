"""标准科室点选直查与确定性号源摘要。"""

import asyncio
from typing import Any

from app.services.chat import AgentChatService
from app.services.chat_guidance import _build_slots_summary


def _slots_payload(*, bookable: bool = True) -> dict[str, Any]:
    doctors = [
        {
            "doctor_id": 21,
            "doctor_name": "周安宁",
            "title": "副主任医师",
            "specialty": "白癜风、银屑病",
            "bookable": bookable,
            "earliest_bookable": {"date": "2026-08-09", "time_slot": "PM"} if bookable else None,
        },
        {
            "doctor_id": 22,
            "doctor_name": "林知远",
            "title": "主治医师",
            "specialty": "湿疹、荨麻疹、痤疮",
            "bookable": bookable,
            "earliest_bookable": {"date": "2026-08-08", "time_slot": "AM"} if bookable else None,
        },
    ]
    return {
        "standard_department": {"id": 5, "name": "皮肤科"},
        "days": [],
        "doctors": doctors,
    }


class NeverAgent:
    async def astream_reply(self, messages, effort, context):
        raise AssertionError("可信科室 ID 直查不得进入 Agent")
        yield


class Directory:
    def __init__(self, result: dict[str, Any] | str) -> None:
        self.result = result
        self.calls: list[tuple[int, float | None, float | None]] = []

    async def list_departments(self, longitude, latitude):
        raise AssertionError("点选直查不得重新拉目录")

    async def get_slots(self, department_id, longitude, latitude):
        self.calls.append((department_id, longitude, latitude))
        return self.result


def _retry_events(directory: Directory) -> list[dict[str, object]]:
    async def collect() -> list[dict[str, object]]:
        service = AgentChatService(NeverAgent(), directory=directory)
        return [
            event
            async for event in service.stream(
                messages=[{"role": "user", "content": "我选择皮肤科"}],
                patient_id=12,
                conversation_id=7,
                effort_choice="quick",
                scenario="triage",
                longitude=113.62,
                latitude=34.75,
                knowledge_source="none",
                retry_standard_department_id=5,
            )
        ]

    return asyncio.run(collect())


def test_summary_uses_earliest_bookable_doctor() -> None:
    assert _build_slots_summary(_slots_payload()) == (
        "已为您查询皮肤科号源：最早可约2026-08-08 上午，当前有号医生2位。"
        "推荐林知远（主治医师），擅长湿疹、荨麻疹、痤疮。"
    )


def test_summary_without_specialty_omits_recommendation() -> None:
    payload = _slots_payload()
    for doctor in payload["doctors"]:
        doctor.pop("specialty")
    assert _build_slots_summary(payload) == (
        "已为您查询皮肤科号源：最早可约2026-08-08 上午，当前有号医生2位。"
    )


def test_earliest_tie_breaks_by_doctor_id() -> None:
    payload = _slots_payload()
    payload["doctors"].reverse()
    for doctor in payload["doctors"]:
        doctor["earliest_bookable"] = {"date": "2026-08-08", "time_slot": "AM"}
    assert "推荐周安宁" in _build_slots_summary(payload)


def test_all_unbookable_uses_empty_summary() -> None:
    assert _build_slots_summary(_slots_payload(bookable=False)) == "皮肤科未来14天暂无可约号源。"


def test_retry_standard_department_id_queries_directly() -> None:
    directory = Directory(_slots_payload())
    events = _retry_events(directory)
    assert [event["event"] for event in events] == ["meta", "message", "department_slots", "done"]
    assert directory.calls == [(5, 113.62, 34.75)]
    assert events[1]["data"]["content"].startswith("已为您查询皮肤科号源")


def test_retry_failure_returns_failed_card_without_agent() -> None:
    events = _retry_events(Directory("查询科室号源失败：连接失败"))
    assert [event["event"] for event in events] == ["meta", "department_slots", "done"]
    assert events[1]["data"]["status"] == "failed"
    assert events[1]["data"]["disclaimer"] == "仅供参考，不替代医生诊断"

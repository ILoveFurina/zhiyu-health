"""智能导诊收敛与确定性科室号源卡流程。

LLM/judge 只帮助把症状收敛到契约中的标准科室；医院、医生、排班和余号事实始终来自
server-java。成功顺序固定为 message 摘要 → department_slots 卡 → done；查询失败仍出
可重试失败卡。预问诊不进入本流程，重复的 resolved 科室卡会被抑制。
"""

from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any

from app.agent.triage import TriageJudge
from app.core.contracts import get_contracts
from app.services.directory import DepartmentDirectory
from app.services.reasoning import Scenario

_CONTRACTS = get_contracts()
_GUIDED = _CONTRACTS.guided_registration
_ONLINE_SCENARIO = _CONTRACTS.online_consultation.scenario
EVENT_MESSAGE = _CONTRACTS.sse_events.stream_events[3]
EVENT_DONE = _CONTRACTS.sse_events.stream_events[4]
_QUERY_STATUSES = frozenset(_GUIDED.resolution_statuses[:2])


@dataclass(frozen=True)
class GuidedRegistrationDecision:
    department_id: int | None = None
    options: tuple[dict[str, Any], ...] = ()


def _department_already_summarized(
    messages: list[dict[str, str]], department_id: int, candidates: list[dict[str, Any]]
) -> bool:
    """只检查最近助手摘要；换科室或用户再次明确挂号仍允许重新查询。"""
    name = next((item.get("name") for item in candidates if item.get("id") == department_id), "")
    if not name:
        return False
    last_assistant = next(
        (message.get("content", "") for message in reversed(messages) if message.get("role") == "assistant"),
        "",
    )
    if not last_assistant:
        return False
    ok_template = _GUIDED.summary_templates["ok"]
    ok_marker = (
        ok_template.split("{department}")[0]
        + name
        + ok_template.split("{department}")[1].split("{", 1)[0]
    )
    empty_marker = name + _GUIDED.summary_templates["empty"].rsplit("}", 1)[-1]
    return ok_marker in last_assistant or empty_marker in last_assistant


def _earliest_key(earliest: dict[str, Any]) -> tuple[str, int]:
    order = list(_GUIDED.time_slot_labels)
    slot = earliest.get("time_slot", "")
    return (earliest.get("date", ""), order.index(slot) if slot in order else len(order))


def _build_slots_summary(payload: dict[str, Any]) -> str:
    """按契约模板拼装号源事实；稳定 tie-break 防止返回列表顺序改变推荐医生。"""
    department = payload.get("standard_department")
    name = department.get("name", "") if isinstance(department, dict) else ""
    doctors = [item for item in payload.get("doctors", []) if isinstance(item, dict)]
    bookable = [item for item in doctors if item.get("bookable")]
    earliest_pool = [
        item for item in bookable if isinstance(item.get("earliest_bookable"), dict)
    ]
    if not bookable or not earliest_pool:
        return _GUIDED.summary_templates["empty"].format(department=name)
    earliest_doctor = min(
        earliest_pool,
        key=lambda item: (_earliest_key(item["earliest_bookable"]), item.get("doctor_id") or 0),
    )
    earliest = earliest_doctor["earliest_bookable"]
    slot = earliest.get("time_slot", "")
    summary = _GUIDED.summary_templates["ok"].format(
        department=name,
        earliest_date=earliest.get("date", ""),
        earliest_slot=_GUIDED.time_slot_labels.get(slot, slot),
        doctor_count=len(bookable),
    )
    specialty = earliest_doctor.get("specialty")
    if specialty:
        summary += _GUIDED.summary_templates["recommendation"].format(
            doctor_name=earliest_doctor.get("doctor_name", ""),
            doctor_title=earliest_doctor.get("title", ""),
            doctor_specialty=specialty,
        )
    return summary


class GuidedRegistrationFlow:
    def __init__(
        self,
        directory: DepartmentDirectory | None,
        triage_judge: TriageJudge,
        disclaimer: str,
    ) -> None:
        self._directory = directory
        self._triage_judge = triage_judge
        self._disclaimer = disclaimer

    async def resolve(
        self,
        messages: list[dict[str, str]],
        retry_department_id: int | None,
        scenario: Scenario,
        longitude: float | None,
        latitude: float | None,
    ) -> GuidedRegistrationDecision:
        if self._directory is None or scenario == _ONLINE_SCENARIO:
            return GuidedRegistrationDecision()
        if retry_department_id is not None:
            return GuidedRegistrationDecision(department_id=retry_department_id)
        candidates = await self._directory.list_departments(longitude, latitude)
        if not isinstance(candidates, list) or not candidates:
            return GuidedRegistrationDecision()
        resolution = await self._triage_judge.judge(messages, candidates)
        department_id = resolution.standard_department_id
        candidate_ids = {item["id"] for item in candidates}
        if (
            resolution.status in _QUERY_STATUSES
            and department_id is not None
            and department_id in candidate_ids
        ):
            resolved = resolution.status == _GUIDED.resolution_statuses[1]
            if resolved and _department_already_summarized(messages, department_id, candidates):
                return GuidedRegistrationDecision()
            return GuidedRegistrationDecision(department_id=department_id)
        if (
            resolution.status == _GUIDED.resolution_statuses[2]
            and resolution.candidate_department_ids
        ):
            names = {item["id"]: item.get("name", "") for item in candidates}
            options = tuple(
                {"id": department_id, "name": names[department_id]}
                for department_id in resolution.candidate_department_ids
                if department_id in names
            )
            return GuidedRegistrationDecision(options=options)
        return GuidedRegistrationDecision()

    async def stream_slots(
        self,
        department_id: int,
        effort: str,
        longitude: float | None,
        latitude: float | None,
    ) -> AsyncIterator[dict[str, object]]:
        assert self._directory is not None
        result = await self._directory.get_slots(department_id, longitude, latitude)
        if isinstance(result, str):
            yield {
                "event": _GUIDED.card_event,
                "data": {
                    "status": _GUIDED.card_statuses[1],
                    "standard_department": {"id": department_id},
                    "message": _GUIDED.summary_templates["failed"],
                    "disclaimer": self._disclaimer,
                },
            }
            yield {"event": EVENT_DONE, "data": {}}
            return
        yield {
            "event": EVENT_MESSAGE,
            "data": {
                "role": "assistant",
                "content": _build_slots_summary(result),
                "disclaimer": self._disclaimer,
                "effort": effort,
            },
        }
        yield {
            "event": _GUIDED.card_event,
            "data": {**result, "status": _GUIDED.card_statuses[0], "disclaimer": self._disclaimer},
        }
        yield {"event": EVENT_DONE, "data": {}}

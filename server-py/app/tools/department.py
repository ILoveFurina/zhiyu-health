"""标准科室目录工具：模型决定参数，目录与号源事实由 server-java 提供。"""

import re
from typing import Any, Protocol

import httpx
from langchain.tools import ToolRuntime
from langchain_core.tools import BaseTool, tool

from app.agent.types import AgentContext
from app.core.contracts import get_contracts
from app.tools.callback import BusinessCallbackClient, callback_error_text, forward_get

_GUIDED = get_contracts().guided_registration


class DepartmentDirectory(Protocol):
    async def list_departments(
        self, longitude: float | None, latitude: float | None
    ) -> list[dict[str, Any]] | str: ...

    async def get_slots(
        self, department_id: int, longitude: float | None, latitude: float | None
    ) -> dict[str, Any] | str: ...


def _location_params(longitude: float | None, latitude: float | None) -> dict[str, Any]:
    params: dict[str, Any] = {}
    if longitude is not None:
        params["longitude"] = longitude
    if latitude is not None:
        params["latitude"] = latitude
    return params


class CallbackDepartmentDirectory:
    """经业务回调读取标准科室目录与跨医院号源。"""

    def __init__(self, client: BusinessCallbackClient) -> None:
        self._client = client

    async def list_departments(
        self, longitude: float | None, latitude: float | None
    ) -> list[dict[str, Any]] | str:
        try:
            payload = await self._client.get(
                "/api/agent/standard-departments", params=_location_params(longitude, latitude)
            )
        except httpx.HTTPError as exc:
            return callback_error_text("查询标准科室", exc)
        if isinstance(payload, dict):
            items = payload.get("departments", payload.get("standard_departments"))
        else:
            items = payload
        if not isinstance(items, list):
            return "查询标准科室失败：业务后端返回格式异常"
        return [
            item
            for item in items
            if isinstance(item, dict)
            and isinstance(item.get("id"), int)
            and isinstance(item.get("name"), str)
        ]

    async def get_slots(
        self, department_id: int, longitude: float | None, latitude: float | None
    ) -> dict[str, Any] | str:
        return await forward_get(
            self._client,
            f"/api/agent/standard-departments/{department_id}/slots",
            params=_location_params(longitude, latitude),
            action="查询科室号源",
        )


def _normalized_name(value: str) -> str:
    return "".join(value.split()).casefold()


def _resolve_names(
    requested: list[str], candidates: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[str]]:
    by_name = {_normalized_name(item["name"]): item for item in candidates}
    selected: list[dict[str, Any]] = []
    unknown: list[str] = []
    seen: set[int] = set()
    for raw_name in requested:
        item = by_name.get(_normalized_name(raw_name))
        if item is None:
            unknown.append(raw_name)
            continue
        department_id = item["id"]
        if department_id not in seen:
            selected.append(item)
            seen.add(department_id)
    return selected, unknown


def _catalog_hint(candidates: list[dict[str, Any]]) -> str:
    names = "、".join(str(item["name"]) for item in candidates)
    return f"可用标准科室为：{names}" if names else "当前没有可用标准科室"


def _context_candidates(context: AgentContext) -> list[dict[str, Any]]:
    return [{"id": item[0], "name": item[1]} for item in context.standard_departments]


def _earliest_key(earliest: dict[str, Any]) -> tuple[str, int]:
    order = list(_GUIDED.time_slot_labels)
    slot = earliest.get("time_slot", "")
    return (earliest.get("date", ""), order.index(slot) if slot in order else len(order))


def build_slots_summary(payload: dict[str, Any]) -> str:
    """只用 server-java 返回值确定性生成号源卡引导摘要。"""
    department = payload.get("standard_department")
    name = department.get("name", "") if isinstance(department, dict) else ""
    doctors = [item for item in payload.get("doctors", []) if isinstance(item, dict)]
    bookable = [item for item in doctors if item.get("bookable")]
    earliest_pool = [item for item in bookable if isinstance(item.get("earliest_bookable"), dict)]
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


def build_department_tools(directory: DepartmentDirectory) -> list[BaseTool]:
    """构造主 Agent 可选择调用的标准科室卡工具。"""

    @tool
    async def get_standard_department_slots(
        department_name: str, runtime: ToolRuntime[AgentContext]
    ) -> dict[str, Any] | str:
        """必须用于明确科室的挂号/预约/号源请求，按标准科室名称查询未来 14 天跨医院号源。"""
        candidates = _context_candidates(runtime.context) or await directory.list_departments(
            runtime.context.longitude, runtime.context.latitude
        )
        if isinstance(candidates, str):
            return candidates
        selected, _ = _resolve_names([department_name], candidates)
        if len(selected) != 1:
            return f"未找到标准科室“{department_name.strip()}”。{_catalog_hint(candidates)}"
        slots = await directory.get_slots(
            selected[0]["id"], runtime.context.longitude, runtime.context.latitude
        )
        if isinstance(slots, str):
            return slots
        return {
            **slots,
            "status": _GUIDED.card_statuses[0],
            "summary": build_slots_summary(slots),
        }

    @tool
    async def suggest_standard_departments(
        department_names: str, runtime: ToolRuntime[AgentContext]
    ) -> dict[str, Any] | str:
        """用于有 2 至 3 个合理科室时；department_names 用中文逗号或顿号分隔正式名称。"""
        candidates = _context_candidates(runtime.context) or await directory.list_departments(
            runtime.context.longitude, runtime.context.latitude
        )
        if isinstance(candidates, str):
            return candidates
        requested = [item for item in re.split(r"[、,，;；\s]+", department_names) if item]
        selected, unknown = _resolve_names(requested, candidates)
        selected = selected[: _GUIDED.options_max_candidates]
        if unknown or len(selected) < 2:
            invalid = "、".join(unknown) if unknown else "候选不足两个"
            return f"候选标准科室无效（{invalid}）。{_catalog_hint(candidates)}"
        return {
            "standard_departments": [{"id": item["id"], "name": item["name"]} for item in selected]
        }

    return [get_standard_department_slots, suggest_standard_departments]

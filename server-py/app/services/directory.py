"""标准科室目录与科室号源：server-java 只读能力的适配器。

两个端点均由编排代码（services/chat.py）确定性调用，不作为 LLM 工具暴露：
- GET /api/agent/standard-departments：候选标准科室目录（科室解析的输入）
- GET /api/agent/standard-departments/{id}/slots：未来 14 天跨医院科室号源

坐标是可信设备数据，由调用方从 AgentContext 取用传入，不作为模型入参；
未授权定位时省略坐标参数。失败语义与业务工具一致：规整为中文错误文本 str，
成功返回 dict/list，调用方用 isinstance 判定成败；失败不得上抛掐断 SSE 流。
"""

from typing import Any, Protocol

import httpx

from app.tools.callback import BusinessCallbackClient, callback_error_text, forward_get


class DepartmentDirectory(Protocol):
    """标准科室目录/号源能力 seam：测试以 MockTransport 或 fake 替换。"""

    async def list_departments(
        self, longitude: float | None, latitude: float | None
    ) -> list[dict[str, Any]] | str:
        """候选标准科室 [{id, name, category}]；失败返回错误文本。"""
        ...

    async def get_slots(
        self, department_id: int, longitude: float | None, latitude: float | None
    ) -> dict[str, Any] | str:
        """科室号源 {standard_department, days, doctors}；失败返回错误文本。"""
        ...


def _location_params(longitude: float | None, latitude: float | None) -> dict[str, Any]:
    params: dict[str, Any] = {}
    if longitude is not None:
        params["longitude"] = longitude
    if latitude is not None:
        params["latitude"] = latitude
    return params


class CallbackDepartmentDirectory:
    """复用 BusinessCallbackClient（鉴权头自带）的生产实现。"""

    def __init__(self, client: BusinessCallbackClient) -> None:
        self._client = client

    async def list_departments(
        self, longitude: float | None, latitude: float | None
    ) -> list[dict[str, Any]] | str:
        try:
            payload = await self._client.get(
                "/api/agent/standard-departments", params=_location_params(longitude, latitude)
            )
        except httpx.HTTPError as e:
            return callback_error_text("查询标准科室", e)
        # server-java StandardDepartmentCatalog 包装为 {departments: [...]}；裸列表形态容错
        if isinstance(payload, dict):
            items = payload.get("departments", payload.get("standard_departments"))
        else:
            items = payload
        if not isinstance(items, list):
            return "查询标准科室失败：业务后端返回格式异常"
        return [
            d for d in items
            if isinstance(d, dict) and isinstance(d.get("id"), int) and isinstance(d.get("name"), str)
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

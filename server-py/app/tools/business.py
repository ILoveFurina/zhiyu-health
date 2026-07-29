"""业务工具薄壳：Agent 层的业务读写一律 HTTP 回调 server-java（无业务写入权）。

知识检索（Neo4j/pgvector 只读）不经过本模块，由 db/ 直连；这里只承载到
server-java 统一入口的回调通道，地址经 SERVER_JAVA_BASE_URL 配置。
具体业务工具（挂号、排班查询等）由后续票在此薄壳上逐个实现。
"""

from typing import Any

import httpx
from langchain.tools import ToolRuntime
from langchain_core.tools import BaseTool, tool

from app.agent.runner import AgentContext


class BusinessCallbackClient:
    def __init__(
        self,
        base_url: str,
        timeout: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
        callback_secret: str = "",
    ) -> None:
        # transport 注入只改变 I/O seam，生产默认仍使用 HTTPX 网络传输。
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout,
            transport=transport,
            headers={"X-Agent-Callback-Token": callback_secret} if callback_secret else None,
        )

    async def _request_json(self, method: str, path: str, **kwargs: Any) -> Any:
        response = await self._client.request(method, path, **kwargs)
        response.raise_for_status()
        return response.json()

    async def get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        return await self._request_json("GET", path, params=params)

    async def post(self, path: str, payload: dict[str, Any]) -> Any:
        return await self._request_json("POST", path, json=payload)

    async def aclose(self) -> None:
        await self._client.aclose()


async def _forward_get(
    client: BusinessCallbackClient, path: str, params: dict[str, Any] | None = None
) -> dict[str, Any]:
    """GET 转发并规整为 dict：LLM 工具出参必须可 JSON 序列化。"""
    return dict(await client.get(path, params))


async def _forward_post(
    client: BusinessCallbackClient, path: str, payload: dict[str, Any]
) -> dict[str, Any]:
    """POST 转发并规整为 dict：LLM 工具出参必须可 JSON 序列化。"""
    return dict(await client.post(path, payload))


def build_business_tools(client: BusinessCallbackClient) -> list[BaseTool]:
    """装配 Agent 可调用的业务工具；函数本身只校验参数并转发到业务后端。"""

    @tool
    async def recommend_doctors(department_name: str) -> dict[str, Any]:
        """按科室名称查询当前仍有号源的医生，用于导诊后的医生推荐。"""
        return await _forward_get(
            client, "/api/agent/doctors/recommend", {"department_name": department_name}
        )

    @tool
    async def get_doctor_slots(doctor_id: int) -> dict[str, Any]:
        """按医生 ID 查询当前可预约的日期、时段和剩余号源。"""
        return await _forward_get(client, f"/api/agent/doctors/{doctor_id}/slots")

    @tool
    async def create_appointment(
        schedule_id: int,
        condition_summary: str,
        runtime: ToolRuntime[AgentContext],
    ) -> dict[str, Any]:
        """为当前患者预约所选排班；成功后自动保存本次会话的病情摘要。"""
        if schedule_id <= 0:
            raise ValueError("schedule_id 必须为正整数")
        if not condition_summary.strip():
            raise ValueError("condition_summary 不能为空")
        return await _forward_post(
            client,
            "/api/agent/appointments",
            {
                "patient_id": runtime.context.patient_id,
                "conversation_id": runtime.context.conversation_id,
                "schedule_id": schedule_id,
                "condition_summary": condition_summary.strip(),
            },
        )

    @tool
    async def get_appointment(runtime: ToolRuntime[AgentContext]) -> dict[str, Any]:
        """查询当前患者自己的挂号列表。"""
        return await _forward_get(
            client, "/api/agent/appointments", {"patient_id": runtime.context.patient_id}
        )

    @tool
    async def find_hospitals(runtime: ToolRuntime[AgentContext]) -> dict[str, Any]:
        """按当前定位查询就近医院（含距离与地址），用于用户说出症状或科室后的就近推荐。

        不接收经纬度入参：坐标是可信设备数据，从注入的 context 直接取用，
        避免模型誊抄数字出错。未授权定位时返回降级提示，由模型引导用户手动选区。
        """
        longitude = runtime.context.longitude
        latitude = runtime.context.latitude
        if longitude is None or latitude is None:
            return {"hospitals": [], "need_location": True}
        return await _forward_get(
            client,
            "/api/agent/hospitals/nearby",
            {"longitude": longitude, "latitude": latitude},
        )

    return [
        recommend_doctors,
        get_doctor_slots,
        find_hospitals,
        create_appointment,
        get_appointment,
    ]

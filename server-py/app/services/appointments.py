"""挂号回调流程：业务状态仍由 server-java 独占，这里只编排 Agent 的两阶段调用。"""

from typing import Any, Protocol

import httpx


class AppointmentCallback(Protocol):
    async def post(self, path: str, payload: dict[str, Any]) -> Any: ...


async def create_with_summary(
    client: AppointmentCallback,
    *,
    patient_id: int,
    conversation_id: int,
    schedule_id: int,
    condition_summary: str,
) -> dict[str, Any]:
    appointment = dict(
        await client.post(
            "/api/agent/appointments",
            {
                "patient_id": patient_id,
                "conversation_id": conversation_id,
                "schedule_id": schedule_id,
            },
        )
    )
    # 幂等重试可能返回已有且已带摘要的挂号单；此时不得用新会话重复回写或降级成功状态。
    if appointment.get("summary_sent"):
        return appointment
    try:
        result = await client.post(
            f"/api/agent/appointments/{appointment['appointment_id']}/summary",
            {
                "patient_id": patient_id,
                "conversation_id": conversation_id,
                "condition_summary": condition_summary,
            },
        )
        return dict(result)
    except httpx.HTTPError:
        # 挂号已经提交，摘要失败只能降级提示，绝不能把成功挂号伪装成整体失败。
        return {
            **appointment,
            "summary_sent": False,
            "notice": "挂号成功，病情摘要暂未发送",
        }

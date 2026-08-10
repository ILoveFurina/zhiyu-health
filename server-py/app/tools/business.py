"""提供给 LLM 的业务工具。

患者与会话身份来自可信运行上下文，不是模型参数。工具只做业务参数校验并回调
server-java；售罄、网络失败或模型臆造参数会变成可解释文本，让模型继续回复而不是
掐断 SSE。HTTP 通道和预问诊摘要回调分别位于相邻模块。
"""

from typing import Any
from langchain.tools import ToolRuntime
from langchain_core.tools import BaseTool, tool

from app.agent.types import AgentContext
from app.core.contracts import get_contracts
from app.tools.callback import BusinessCallbackClient, forward_get, forward_post

__all__ = ["build_business_tools"]

# OTC 来源标记的单一事实源是 contracts/order-flow.json；otc-prepare 回调不回传 source，
# 由工具补进结果供模型叙述与预览卡投影区分两条路径。
_OTC_SOURCE = get_contracts().order_flow.sources["otc"]


def _appointment_args_error(schedule_id: int, condition_summary: str) -> str | None:
    """入参由模型生成，臆造值属正常运行时结果：返回错误文本让其改正，而非掐断流。"""
    if schedule_id <= 0:
        return "预约挂号失败：schedule_id 无效，请先查询可约排班再预约"
    if not condition_summary.strip():
        return "预约挂号失败：病情摘要为空，请先向用户了解主要症状再预约"
    return None


def _otc_prepare_args_error(medication_id: int | None, quantity: int | None) -> str | None:
    """OTC 购药预览入参由模型生成：臆造值属正常运行时结果，返回错误文本让其改正。"""
    if medication_id is None or medication_id <= 0:
        return "购药预览失败：medication_id 无效，请先查药再装配预览卡"
    if quantity is None or quantity < 1:
        return "购药预览失败：购买数量必须为正整数，请先向用户确认数量"
    return None


def build_business_tools(client: BusinessCallbackClient) -> list[BaseTool]:
    """装配 Agent 可调用的业务工具；函数本身只校验参数并转发到业务后端。"""

    @tool
    async def recommend_doctors(department_name: str) -> dict[str, Any] | str:
        """按科室名称查询当前仍有号源的医生，用于导诊后的医生推荐。"""
        return await forward_get(
            client,
            "/api/agent/doctors/recommend",
            {"department_name": department_name},
            action="查询医生推荐",
        )

    @tool
    async def get_doctor_slots(doctor_id: int) -> dict[str, Any] | str:
        """按医生 ID 查询当前可预约的日期、时段和剩余号源。"""
        return await forward_get(client, f"/api/agent/doctors/{doctor_id}/slots", action="查询号源")

    @tool
    async def create_appointment(
        schedule_id: int,
        condition_summary: str,
        runtime: ToolRuntime[AgentContext],
    ) -> dict[str, Any] | str:
        """为当前患者预约所选排班；成功后自动保存本次会话的病情摘要。"""
        args_error = _appointment_args_error(schedule_id, condition_summary)
        if args_error is not None:
            return args_error
        return await forward_post(
            client,
            "/api/agent/appointments",
            {
                "patient_id": runtime.context.patient_id,
                "conversation_id": runtime.context.conversation_id,
                "schedule_id": schedule_id,
                "condition_summary": condition_summary.strip(),
            },
            action="预约挂号",
        )

    @tool
    async def get_appointment(runtime: ToolRuntime[AgentContext]) -> dict[str, Any] | str:
        """查询当前患者自己的挂号列表。"""
        return await forward_get(
            client,
            "/api/agent/appointments",
            {"patient_id": runtime.context.patient_id},
            action="查询挂号记录",
        )

    @tool
    async def search_medications(name: str) -> dict[str, Any] | str:
        """按药名查询平台标准药品目录，供用户点名买药时定位标准药品。

        目录条目带 is_prescription 处方属性；命中处方药时不得按 OTC 购药，
        引导用户凭已审核处方购药或先咨询医生。不得编造药品。
        """
        return await forward_get(
            client,
            "/api/agent/medications",
            {"name": name.strip()},
            action="查询药品",
        )

    @tool
    async def list_approved_prescriptions(
        runtime: ToolRuntime[AgentContext],
    ) -> dict[str, Any] | str:
        """查询当前患者已审核通过的电子处方，含药品明细与用法用量，供处方药购药选处方。"""
        return await forward_get(
            client,
            "/api/agent/prescriptions",
            {"patient_id": runtime.context.patient_id},
            action="查询已审核处方",
        )

    return [
        recommend_doctors,
        get_doctor_slots,
        create_appointment,
        get_appointment,
        search_medications,
        list_approved_prescriptions,
        _build_prepare_drug_order_tool(client),
    ]


def _build_prepare_drug_order_tool(client: BusinessCallbackClient) -> BaseTool:
    """购药预览卡工具独立装配：处方药/OTC 双分支回调不同端点，拆出避免主装配函数超复杂度。

    处方药走 prepare 拿处方固化院区与锁定院区药房；OTC 走 otc-prepare 校验明细。
    两个端点都只读，不扣库存不建订单。
    """

    @tool
    async def prepare_drug_order(
        runtime: ToolRuntime[AgentContext],
        medication_id: int | None = None,
        quantity: int | None = None,
        prescription_id: int | None = None,
    ) -> dict[str, Any] | str:
        """装配购药预览卡所需数据（处方药返回锁定院区药房，OTC 校验明细），不扣库存不建订单。

        二选一：处方药传 prescription_id；OTC 传 medication_id + quantity。
        患者身份从可信上下文取（不入模型可见参数），供处方归属校验。
        预览卡只展示非敏感稳定事实，价格与库存以用户在统一购药确认页看到的实时结果为准；
        下单不在对话内发生，用户点击预览卡进入确认页后才决定是否购买。
        """
        patient_id = runtime.context.patient_id
        if prescription_id is not None:
            if prescription_id <= 0:
                return "购药预览失败：prescription_id 无效，请先查询已审核处方"
            return await forward_get(
                client,
                "/api/agent/drug-orders/prepare",
                {"patient_id": patient_id, "prescription_id": prescription_id},
                action="装配购药预览卡",
            )
        args_error = _otc_prepare_args_error(medication_id, quantity)
        if args_error is not None:
            return args_error
        result = await forward_get(
            client,
            "/api/agent/drug-orders/otc-prepare",
            {"patient_id": patient_id, "items": f"{medication_id}:{quantity}"},
            action="校验 OTC 购药明细",
        )
        # otc-prepare 只回明细回声，补上来源标记供模型叙述与预览卡投影区分两条路径
        if isinstance(result, dict):
            return {"source": _OTC_SOURCE, **result}
        return result

    return prepare_drug_order

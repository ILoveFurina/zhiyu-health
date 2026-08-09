"""提供给 LLM 的业务工具。

患者与会话身份来自可信运行上下文，不是模型参数。工具只做业务参数校验并回调
server-java；售罄、网络失败或模型臆造参数会变成可解释文本，让模型继续回复而不是
掐断 SSE。HTTP 通道和预问诊摘要回调分别位于相邻模块。
"""

from typing import Any
from langchain.tools import ToolRuntime
from langchain_core.tools import BaseTool, tool

from app.agent.types import AgentContext
from app.tools.callback import BusinessCallbackClient, forward_get, forward_post

__all__ = ["build_business_tools"]


def _appointment_args_error(schedule_id: int, condition_summary: str) -> str | None:
    """入参由模型生成，臆造值属正常运行时结果：返回错误文本让其改正，而非掐断流。"""
    if schedule_id <= 0:
        return "预约挂号失败：schedule_id 无效，请先查询可约排班再预约"
    if not condition_summary.strip():
        return "预约挂号失败：病情摘要为空，请先向用户了解主要症状再预约"
    return None


def _otc_prepare_args_error(medication_id: int | None, quantity: int | None) -> str | None:
    """OTC 购药确认入参由模型生成：臆造值属正常运行时结果，返回错误文本让其改正。"""
    if medication_id is None or medication_id <= 0:
        return "购药确认失败：medication_id 无效，请先查药再确认购药"
    if quantity is None or quantity < 1:
        return "购药确认失败：购买数量必须为正整数"
    return None


def _drug_order_prepare_params(
    patient_id: int, medication_id: int | None, quantity: int | None, prescription_id: int | None
) -> dict[str, Any] | str:
    """装配购药确认回调参数：处方药传 prescription_id，OTC 传 medication_id + quantity。

    入参由模型生成，臆造值属正常运行时结果：返回错误文本让其改正，而非掐断流。
    患者身份从可信上下文取（不入模型可见参数），供处方归属校验。
    """
    params: dict[str, Any] = {"patient_id": patient_id}
    if prescription_id is not None:
        if prescription_id <= 0:
            return "购药确认失败：prescription_id 无效，请先查询已审核处方"
        params["prescription_id"] = prescription_id
        return params
    args_error = _otc_prepare_args_error(medication_id, quantity)
    if args_error is not None:
        return args_error
    params["medication_id"] = medication_id
    params["quantity"] = quantity
    return params


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
        """按药名模糊查询在售非处方药（OTC），供用户点名买药时查药；只返回可直接下单的药品。"""
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

    @tool
    async def prepare_drug_order(
        runtime: ToolRuntime[AgentContext],
        medication_id: int | None = None,
        quantity: int | None = None,
        prescription_id: int | None = None,
    ) -> dict[str, Any] | str:
        """装配购药确认卡所需数据（实时单价/库存/总价测算），不扣库存不建订单。

        二选一：OTC 传 medication_id + quantity；处方药传 prescription_id。
        患者身份从可信上下文取，供处方归属校验。
        """
        params = _drug_order_prepare_params(
            runtime.context.patient_id, medication_id, quantity, prescription_id
        )
        if isinstance(params, str):
            return params
        return await forward_get(
            client,
            "/api/agent/drug-orders/prepare",
            params,
            action="装配购药确认卡",
        )

    return [
        recommend_doctors,
        get_doctor_slots,
        create_appointment,
        get_appointment,
        search_medications,
        list_approved_prescriptions,
        prepare_drug_order,
    ]

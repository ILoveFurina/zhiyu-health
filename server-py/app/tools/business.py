"""业务工具薄壳：Agent 层的业务读写一律 HTTP 回调 server-java（无业务写入权）。

知识检索（Neo4j/pgvector 只读）不经过本模块，由 db/ 直连；这里只承载到
server-java 统一入口的回调通道，地址经 SERVER_JAVA_BASE_URL 配置。
具体业务工具（挂号、排班查询等）由后续票在此薄壳上逐个实现。

回调失败（售罄 409、server-java 不可用、参数被模型臆造）是正常运行时结果，必须规整为
模型可向用户解释的错误文本，而非上抛异常--ToolNode 默认只兜参数校验错误，
执行期异常会穿透图掐断整条 SSE 流（票 33）。错误文本不是 JSON dict，
不会被 runner._tool_output 投影成卡片，与"工具错误仍会回到模型解释"的设计一致。

预问诊摘要回调（票 55）：摘要判定不再阻塞 message 事件，改为后台 task 异步执行，
完成后经本模块的 PreconsultationSummaryCallback 回调 server-java 落草稿。
回调失败只 log.warning 不抛--与摘要降级语义一致（失败保留上一版，不打断对话流）。
"""

import logging
from typing import Any, Protocol

import httpx
from langchain.tools import ToolRuntime
from langchain_core.tools import BaseTool, tool

from app.agent.runner import AgentContext

logger = logging.getLogger("app.tools.business")


class BusinessCallbackClient:
    def __init__(
        self,
        base_url: str,
        timeout: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
        callback_secret: str = "",
    ) -> None:
        # transport 注入只改变 I/O seam，生产默认仍使用 HTTPX 网络传输。
        # trust_env=False：httpx 默认 trust_env=True 会从环境变量与 Windows 系统代理
        # （注册表）解析代理，把 server-java 业务回调塞进本机代理导致全 502
        # （预问诊科室恒 null、导诊工具全失败，票 60 后续发现）。server-java 是内网
        # 直连目标，必须绕过系统代理；MockTransport 测试不触网不受影响。
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout,
            transport=transport,
            trust_env=False,
            headers={"X-Agent-Callback-Token": callback_secret} if callback_secret else None,
        )
        self._callback_secret = callback_secret

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


def _callback_error_text(action: str, error: httpx.HTTPError) -> str:
    """把回调失败规整为模型可向用户解释的错误文本（server-java 错误体为 {"detail": ...}）。"""
    if isinstance(error, httpx.HTTPStatusError):
        detail: str | None = None
        try:
            body = error.response.json().get("detail")
        except ValueError:
            body = None  # 非 JSON 错误体没有可读文案
        if isinstance(body, dict):
            detail = body.get("message") if isinstance(body.get("message"), str) else None
        elif isinstance(body, str):
            detail = body
        return f"{action}失败：{detail or f'HTTP {error.response.status_code}'}"
    return f"{action}失败：业务后端暂不可用，请稍后重试"


async def _forward_get(
    client: BusinessCallbackClient, path: str, params: dict[str, Any] | None = None, *, action: str
) -> dict[str, Any] | str:
    """GET 转发并规整为 dict：LLM 工具出参必须可 JSON 序列化；失败降级为错误文本。"""
    try:
        return dict(await client.get(path, params=params))
    except httpx.HTTPError as e:
        return _callback_error_text(action, e)


async def _forward_post(
    client: BusinessCallbackClient, path: str, payload: dict[str, Any], *, action: str
) -> dict[str, Any] | str:
    """POST 转发并规整为 dict：LLM 工具出参必须可 JSON 序列化；失败降级为错误文本。"""
    try:
        return dict(await client.post(path, payload))
    except httpx.HTTPError as e:
        return _callback_error_text(action, e)


def _appointment_args_error(schedule_id: int, condition_summary: str) -> str | None:
    """入参由模型生成，臆造值属正常运行时结果：返回错误文本让其改正，而非掐断流。"""
    if schedule_id <= 0:
        return "预约挂号失败：schedule_id 无效，请先查询可约排班再预约"
    if not condition_summary.strip():
        return "预约挂号失败：病情摘要为空，请先向用户了解主要症状再预约"
    return None


class SummaryCallback(Protocol):
    """预问诊摘要异步回调：后台 task 算出摘要后回调 server-java 落草稿。

    失败必须吞异常（只记日志），与摘要降级语义一致--回调失败只是本轮草稿保留上一版，
    不打断对话流、不影响已下发的 message/done（硬约束：摘要不是业务关键路径）。
    """

    async def apply(self, draft_id: int, payload: dict[str, Any]) -> None:
        ...


class PreconsultationSummaryCallback:
    """经 BusinessCallbackClient 回调 server-java 摘要落库端点（fire-and-forget）。

    复用业务回调通道（X-Agent-Callback-Token 鉴权、base_url 指向 server-java）。
    POST /api/agent/preconsultation-drafts/{draft_id}/summary，body 为摘要快照。
    任一环节失败只 log.warning 不抛--摘要回调不在请求路径上，失败等同本轮降级。
    """

    def __init__(self, client: BusinessCallbackClient) -> None:
        self._client = client

    async def apply(self, draft_id: int, payload: dict[str, Any]) -> None:
        try:
            await self._client.post(f"/api/agent/preconsultation-drafts/{draft_id}/summary", payload)
        except httpx.HTTPError as error:
            # 不记 payload 原文（含病情摘要属患者敏感信息，硬约束 5），只记异常类与 draftId
            logger.warning(
                "preconsultation summary callback failed draftId=%s error=%s",
                draft_id,
                error.__class__.__name__,
            )


def build_business_tools(client: BusinessCallbackClient) -> list[BaseTool]:
    """装配 Agent 可调用的业务工具；函数本身只校验参数并转发到业务后端。"""

    @tool
    async def recommend_doctors(department_name: str) -> dict[str, Any] | str:
        """按科室名称查询当前仍有号源的医生，用于导诊后的医生推荐。"""
        return await _forward_get(
            client, "/api/agent/doctors/recommend", {"department_name": department_name},
            action="查询医生推荐",
        )

    @tool
    async def get_doctor_slots(doctor_id: int) -> dict[str, Any] | str:
        """按医生 ID 查询当前可预约的日期、时段和剩余号源。"""
        return await _forward_get(client, f"/api/agent/doctors/{doctor_id}/slots", action="查询号源")

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
        return await _forward_post(
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
        return await _forward_get(
            client,
            "/api/agent/appointments",
            {"patient_id": runtime.context.patient_id},
            action="查询挂号记录",
        )

    return [
        recommend_doctors,
        get_doctor_slots,
        create_appointment,
        get_appointment,
    ]

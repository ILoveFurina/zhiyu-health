"""Agent 对话编排：推理档位映射 -> 知识源选择 -> Agent 流式回复 -> 免责声明注入。

server-py 只承载 LLM/工具循环与表达（ADR-0009）：鉴权、审计、红线规则与
会话持久化都在 server-java 入口侧。全部 AI 产出在事件负载中统一注入
免责声明（硬规则 1，server-java 出口另有兜底校验）。

知识增强（ADR-0010）：知识源选择器模型 Y 二态 rag/graph + 自动降级。默认按
场景（triage->rag，interpretation->none）；rag 注入 search_knowledge 工具、graph
注入 traverse_graph 工具（LLM 自主调用，互斥），关闭增强不注入工具（裸 LLM）。
检索失败/空召回静默降级走裸 LLM；graph 遍历器未配置视为 unavailable 降级。
降级/不可用状态经 SSE knowledge 元事件暴露。
召回块与 knowledge 元事件不带免责声明（非 AI 产出）。

票 50 智能导诊强制号源查询：meta 之后由编排代码（非 LLM）决定是否直查
server-java 只读号源——请求携带 retry_standard_department_id 时直查；
否则拉候选标准科室做科室解析（triage judge）；仅当用户明确挂号意图
（explicit_booking）时强制查询。命中时先产出确定性摘要 message
事件、再产出 department_slots 卡片事件、最后 done，不进入 Agent 流；
未命中（含纯症状收敛 resolved，走 LLM 工具流）或目录不可用时退回正常
Agent 流。摘要措辞由代码按契约模板拼装，
LLM 不参与医院、医生、排班、余号等事实的生成。
票 65：judge 判 ambiguous 且产出候选科室时，Agent 文字流照常，编排层在
message 事件后追加 department_options 科室选择卡（候选 id 来自 judge、
名称按 id 从候选目录确定性查出），不短路、不查号源。

票 55 预问诊场景（preconsultation，场景值取契约 online-consultation.json）：
跳过强制号源查询；runner 按场景隔离业务工具并选专用提示词；每个成功轮次后
message 事件只携带 emotion（安抚语），不再阻塞等待摘要判定。摘要改为后台
fire-and-forget task 异步执行，完成后回调 server-java 落草稿（客户端轮询回拉
刷新 CTA）。判定失败/超时降级省略回调，保留上一版，不掐断流、不阻塞输入。
"""

import asyncio
import logging
from collections.abc import AsyncIterator
from typing import Any

from app.agent.emotion import EmotionJudge, LazyEmotionJudge
from app.agent.preconsult import LazyPreconsultJudge, PreconsultJudge
from app.agent.runner import AgentContext, AgentOutput, AgentRunner, HealthProfileContext
from app.agent.triage import LazyTriageJudge, TriageJudge
from app.core.contracts import get_contracts
from app.schemas.emotion import emotion_soothing_text
from app.services.directory import DepartmentDirectory
from app.services.reasoning import EffortChoice, Scenario, map_reasoning_effort
from app.tools.business import SummaryCallback

logger = logging.getLogger("app.services.chat")

# SSE 流事件名唯一事实源是 contracts/sse-events.json；协议顺序固定，
# 由 tests/test_contract_consumption.py 与 java 侧 ContractsConsistencyTest 双端钉死。
EVENT_META, EVENT_KNOWLEDGE, EVENT_TOKEN, EVENT_MESSAGE, EVENT_DONE = (
    get_contracts().sse_events.stream_events
)

# 工具进度事件名（票 24）：tool_start/tool_end，不带免责声明（非 AI 产出）。
EVENT_TOOL_START, EVENT_TOOL_END = get_contracts().sse_events.trace_events
EVENT_THINKING = get_contracts().chat_realtime.thinking_event

# 票 50/62：触发强制号源查询的解析状态为契约 resolution_statuses 前两态
# （explicit_booking=明确挂号意图、resolved=症状收敛到单一科室），与契约的
# 一致性由 tests/test_contract_consumption.py 钉死。票 62：resolved 收敛即
# 直查出卡，不再等用户开口；同一科室已出卡时由去重守卫挡重复（见下）。
_GUIDED = get_contracts().guided_registration
_QUERY_STATUSES = frozenset(_GUIDED.resolution_statuses[:2])


def _project_agent_output(
    output: AgentOutput, parts: list[str], disclaimer: str
) -> dict[str, object] | None:
    """将 runner 输出映射为实时事件；thinking 不进入正文聚合。"""
    if output.event == EVENT_TOKEN and isinstance(output.data, str):
        parts.append(output.data)
        return {"event": EVENT_TOKEN, "data": {"text": output.data}}
    if output.event == EVENT_THINKING and isinstance(output.data, str):
        return {"event": EVENT_THINKING, "data": output.data}
    if output.event == EVENT_KNOWLEDGE and isinstance(output.data, dict):
        return {"event": EVENT_KNOWLEDGE, "data": output.data}
    if output.event in (EVENT_TOOL_START, EVENT_TOOL_END):
        return {"event": output.event, "data": output.data}
    if isinstance(output.data, dict):
        return {"event": output.event, "data": {**output.data, "disclaimer": disclaimer}}
    return None


def _department_already_summarized(
    messages: list[dict[str, str]],
    department_id: int,
    candidates: list[dict[str, Any]],
) -> bool:
    """票 62 去重守卫：同一科室最近一条助手消息已是号源摘要时不再重复直查。

    比对锚点从契约摘要模板派生（ok 前缀"已为您查询{科室}号源"、empty 后缀
    "{科室}未来14天暂无可约号源"），只看最近一条助手消息：换科室收敛仍正常
    出卡，explicit_booking（用户再次明确挂号）不经过本守卫。卡片 JSON 不进
    LLM 上下文（server-java recentContext 排除 ai_card_kinds），故最近助手
    消息即上轮摘要文本。
    """
    name = next((c.get("name") for c in candidates if c.get("id") == department_id), "")
    if not name:
        return False
    last_assistant = next(
        (m.get("content", "") for m in reversed(messages) if m.get("role") == "assistant"),
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

# 票 55：预问诊场景值与摘要快照事件字段名，唯一事实源是 contracts/online-consultation.json
_ONLINE = get_contracts().online_consultation


def _earliest_key(guided: Any, earliest: dict[str, Any]) -> tuple[str, int]:
    """最早可约排序键：日期升序，同日按契约 time_slot_labels 顺序（AM 先于 PM）。"""
    order = list(guided.time_slot_labels)
    slot = earliest.get("time_slot", "")
    return (earliest.get("date", ""), order.index(slot) if slot in order else len(order))


def _build_slots_summary(payload: dict[str, Any]) -> str:
    """按契约 summary_templates 确定性拼装摘要（不让 LLM 生成号源事实）。

    ok：最早可约取所有 bookable 医生 earliest_bookable 的最小值，doctor_count 为
    bookable 医生数；无 bookable 医生（全部无号）用 empty 模板。
    票 60：摘要末尾确定性拼接最早可约医生的推荐子句（recommendation 模板），
    该医生无 specialty（缺失或空串）时整句省略，摘要保持原状。
    """
    guided = _GUIDED
    department = payload.get("standard_department")
    name = department.get("name", "") if isinstance(department, dict) else ""
    doctors = [d for d in payload.get("doctors", []) if isinstance(d, dict)]
    bookable = [d for d in doctors if d.get("bookable")]
    earliest_pool = [d for d in bookable if isinstance(d.get("earliest_bookable"), dict)]
    if not bookable or not earliest_pool:
        return guided.summary_templates["empty"].format(department=name)
    # doctor_id 做同时间点稳定 tie-break，避免列表顺序决定推荐医生
    earliest_doctor = min(
        earliest_pool, key=lambda d: (_earliest_key(guided, d["earliest_bookable"]), d.get("doctor_id") or 0)
    )
    earliest = earliest_doctor["earliest_bookable"]
    slot = earliest.get("time_slot", "")
    summary = guided.summary_templates["ok"].format(
        department=name,
        earliest_date=earliest.get("date", ""),
        earliest_slot=guided.time_slot_labels.get(slot, slot),
        doctor_count=len(bookable),
    )
    specialty = earliest_doctor.get("specialty")
    if specialty:
        summary += guided.summary_templates["recommendation"].format(
            doctor_name=earliest_doctor.get("doctor_name", ""),
            doctor_title=earliest_doctor.get("title", ""),
            doctor_specialty=specialty,
        )
    return summary


class AgentChatService:
    def __init__(
        self,
        agent_runner: AgentRunner,
        *,
        rag_available: bool = False,
        graph_available: bool = False,
        emotion_judge: EmotionJudge | None = None,
        triage_judge: TriageJudge | None = None,
        preconsult_judge: PreconsultJudge | None = None,
        directory: DepartmentDirectory | None = None,
        summary_callback: SummaryCallback | None = None,
    ) -> None:
        self._agent_runner = agent_runner
        self._rag_available = rag_available
        self._graph_available = graph_available
        # 情绪反馈判断器（票 44，ADR-0019）：主回复完成后串行二次非流式 LLM 调用；
        # 默认懒装配，测试可注入 fake 断言调用与降级行为。
        self._emotion_judge = emotion_judge or LazyEmotionJudge()
        # 票 50：科室解析判定器（默认懒装配）与标准科室目录/号源能力。
        # directory 为 None 视为能力未装配：跳过解析与强制查询，退回正常 Agent 流。
        self._triage_judge = triage_judge or LazyTriageJudge()
        # 票 55：预问诊摘要判定器（默认懒装配，失败降级 None 本轮省略快照）。
        self._preconsult_judge = preconsult_judge or LazyPreconsultJudge()
        # 票 55：摘要异步回调 client（fire-and-forget）；None 时跳过回调（测试注入）。
        self._summary_callback = summary_callback
        # 最近一次摘要后台 task 引用：fire-and-forget 生产代码不持有，仅供测试 await 确定性断言。
        self._last_summary_task: asyncio.Task[None] | None = None
        self._directory = directory
        # 免责声明唯一事实源是跨栈契约 contracts/disclaimer.json（硬约束 1），
        # 装配期取出缓存，禁止在本地另立文案常量。
        self._disclaimer = get_contracts().disclaimer.text
        self._knowledge = get_contracts().knowledge

    def _resolve_knowledge_source(self, requested: str | None, scenario: Scenario) -> str:
        """知识源选择器（模型 Y）：默认按场景；graph 需遍历器可用；rag 需检索器可用。"""
        contract = self._knowledge
        source = requested if requested is not None else contract.default_by_scenario[scenario]
        if source not in contract.knowledge_sources:
            return contract.none_source
        if source == "rag" and not self._rag_available:
            # rag 选中但检索器未配置：降级走裸 LLM（knowledge 事件标 degraded）
            return contract.none_source
        if source == "graph" and not self._graph_available:
            # graph 选中但遍历器未配置：降级走裸 LLM（knowledge 事件标 unavailable）
            return contract.none_source
        return source

    def _degraded_knowledge_event(
        self, requested: str | None, scenario: Scenario
    ) -> dict[str, object] | None:
        """降级/不可用时返回 knowledge 元事件；成功检索的 ok 事件由 runner 产出。"""
        contract = self._knowledge
        # 请求态（用户显式或场景默认），用于判断是否本应走增强却降级
        requested_source = (
            requested if requested is not None else contract.default_by_scenario[scenario]
        )
        if requested_source == "graph" and not self._graph_available:
            return {"source": "graph", "status": "unavailable", "count": 0}
        if requested_source == "rag" and not self._rag_available:
            return {"source": "rag", "status": "degraded", "count": 0}
        return None

    async def _resolve_forced_department(
        self,
        messages: list[dict[str, str]],
        retry_standard_department_id: int | None,
        scenario: Scenario,
        longitude: float | None,
        latitude: float | None,
    ) -> tuple[int | None, list[dict[str, Any]]]:
        """票 50：决定是否强制查询科室号源；票 65：同时带出 ambiguous 选择卡候选。

        返回 (强制查询科室 ID 或 None, 选择卡科室列表)。重试字段非空 = 复用已确定
        科室直查（跳过目录拉取、解析与 Agent 流）；目录不可用/无候选/解析未收敛/
        ID 越界均返回 (None, [])，退回正常 Agent 流。预问诊场景（票 55）直接短路：
        预问诊不进挂号闭环，不直查号源、不出科室号源卡与选择卡。
        票 62：resolved 命中同一已出卡科室时去重返回 None（见 _department_already_summarized）。
        票 65：ambiguous 且 judge 候选非空时返回候选 [{id, name}]（名称按 id 从
        候选目录确定性查出，不让 LLM 生成），供编排层在选择卡事件中下发。
        """
        if self._directory is None or scenario == _ONLINE.scenario:
            return None, []
        if retry_standard_department_id is not None:
            return retry_standard_department_id, []
        candidates = await self._directory.list_departments(longitude, latitude)
        if not isinstance(candidates, list) or not candidates:
            # 目录查询失败（错误文本）或无候选：跳过解析走正常 Agent 流
            return None, []
        resolution = await self._triage_judge.judge(messages, candidates)
        candidate_ids = {c["id"] for c in candidates}
        department_id = resolution.standard_department_id
        if (
            resolution.status not in _QUERY_STATUSES
            or department_id is None
            or department_id not in candidate_ids
        ):
            if (
                resolution.status == _GUIDED.resolution_statuses[2]  # ambiguous
                and resolution.candidate_department_ids
            ):
                names = {c["id"]: c.get("name", "") for c in candidates}
                options = [
                    {"id": dept_id, "name": names[dept_id]}
                    for dept_id in resolution.candidate_department_ids
                    if dept_id in names
                ]
                return None, options
            return None, []
        # 票 62：resolved（resolution_statuses 第二态）命中同一已出卡科室时去重，
        # 避免收敛后每句闲聊都重复推号源卡；explicit_booking 仍可重复直查
        if resolution.status == _GUIDED.resolution_statuses[1] and _department_already_summarized(
            messages, department_id, candidates
        ):
            return None, []
        return department_id, []

    async def _preconsult_summary(
        self,
        messages: list[dict[str, str]],
        assistant_text: str,
        longitude: float | None,
        latitude: float | None,
    ) -> dict[str, object] | None:
        """票 55：预问诊成功轮次后以非流式结构化调用整理病情摘要快照。

        快照输入为本轮完整对话（历史 + 本轮助手回复）。目录不可用按空候选处理
        （建议科室必然归一化为 None）；目录/判定任一环节失败返回 None——本轮
        只省略快照字段，不掐断流，上一版快照由 server-java 侧草稿保留。
        """
        try:
            candidates: list[dict[str, Any]] = []
            if self._directory is not None:
                listed = await self._directory.list_departments(longitude, latitude)
                if isinstance(listed, list):
                    candidates = listed
            round_messages = [*messages, {"role": "assistant", "content": assistant_text}]
            summary = await self._preconsult_judge.judge(round_messages, candidates)
        except Exception:
            return None
        if summary is None:
            return None
        # 摘要属 AI 产出，快照内携带免责声明标注（硬约束 1，契约 _summary_event_field_doc）
        return {**summary.model_dump(), "disclaimer": self._disclaimer}

    async def _department_slots_stream(
        self,
        department_id: int,
        effort: str,
        longitude: float | None,
        latitude: float | None,
    ) -> AsyncIterator[dict[str, object]]:
        """强制号源查询事件序列：成功 = message 摘要 -> 卡片 -> done；失败 = failed 卡 -> done。

        摘要与卡片数据全部来自 server-java 返回值 + 契约模板，LLM 不参与措辞。
        """
        assert self._directory is not None  # 由 _resolve_forced_department 保证
        guided = _GUIDED
        result = await self._directory.get_slots(department_id, longitude, latitude)
        if isinstance(result, str):
            # 失败：普通失败卡（含重新查询入口所需字段），不出空白卡、不静默吞错
            yield {
                "event": guided.card_event,
                "data": {
                    "status": guided.card_statuses[1],  # failed
                    "standard_department": {"id": department_id},
                    "message": guided.summary_templates["failed"],
                    "disclaimer": self._disclaimer,
                },
            }
            yield {"event": EVENT_DONE, "data": {}}
            return
        # 先摘要后卡片：顺序进入实时流与历史会话（回放不丢科室/医生/地址数据）
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
            "event": guided.card_event,
            "data": {
                **result,
                "status": guided.card_statuses[0],  # ok（含全部无号，仍出卡）
                "disclaimer": self._disclaimer,
            },
        }
        yield {"event": EVENT_DONE, "data": {}}

    async def _message_event_data(
        self,
        messages: list[dict[str, str]],
        parts: list[str],
        effort: str,
    ) -> dict[str, object]:
        """message 事件负载组装：回复全文 + 免责声明 + emotion/安抚语。

        摘要快照不再在此阻塞 message 事件（票 55 改造）：改为 stream() 末尾
        done 之后的后台 fire-and-forget task 异步执行并回调 server-java 落草稿。
        摘要判定器仍由编排层持有，但调用时机移出请求路径，避免第二次非流式
        LLM 调用（timeout 15s × 最多 2 次校验重试）阻塞 done 导致客户端输入框锁死。
        """
        # 票 44：主回复 token 流完成后、message 事件发出前，串行非流式 LLM 调用判情绪。
        # emotion 挂 message 事件（不新增 SSE 事件）；判断失败/超时降级 calm 不阻塞回复。
        # 仅取最后一条用户消息作为情绪判断输入（避免把整段历史塞进二次调用）。
        last_user_text = next(
            (m["content"] for m in reversed(messages) if m.get("role") == "user"),
            "",
        )
        emotion_result = await self._emotion_judge.judge(last_user_text)
        soothing = emotion_soothing_text(emotion_result.emotion)
        message_data: dict[str, object] = {
            "role": "assistant",
            "content": "".join(parts),
            "disclaimer": self._disclaimer,
            "effort": effort,
            "emotion": emotion_result.emotion,
        }
        # calm 无安抚语（映射缺省即无）；anxious/fearful 附一条确定性安抚文案，
        # 与回复共用同一条免责声明，不单独标注、不作为独立消息、不进 messages 数组。
        if soothing is not None:
            message_data["soothing_text"] = soothing
        return message_data

    async def stream(
        self,
        *,
        messages: list[dict[str, str]],
        patient_id: int,
        conversation_id: int,
        health_profile: HealthProfileContext | None = None,
        effort_choice: EffortChoice,
        scenario: Scenario,
        longitude: float | None = None,
        latitude: float | None = None,
        knowledge_source: str | None = None,
        retry_standard_department_id: int | None = None,
        preconsultation_draft_id: int | None = None,
        prescription_id: int | None = None,
    ) -> AsyncIterator[dict[str, object]]:
        """对一段消息历史流式生成回复。auto 在此映射为 disabled/high，不外传。"""
        effort = map_reasoning_effort(effort_choice, scenario)
        yield {"event": EVENT_META, "data": {"effort": effort}}

        # 票 50：编排层强制号源查询（不依赖 LLM 自主调工具）；命中即短路 Agent 流
        # （预问诊场景在 _resolve_forced_department 内短路，不进挂号闭环）。
        forced_id, department_options = await self._resolve_forced_department(
            messages, retry_standard_department_id, scenario, longitude, latitude
        )
        if forced_id is not None:
            async for event in self._department_slots_stream(forced_id, effort, longitude, latitude):
                yield event
            return

        effective = self._resolve_knowledge_source(knowledge_source, scenario)
        # 降级/不可用由 service 在 meta 后发 knowledge 元事件；
        # 成功检索（status=ok）的 knowledge 事件由 runner 在工具调用时产出。
        degraded = self._degraded_knowledge_event(knowledge_source, scenario)
        if degraded is not None:
            yield {"event": EVENT_KNOWLEDGE, "data": degraded}

        parts: list[str] = []
        context = AgentContext(
            patient_id=patient_id,
            conversation_id=conversation_id,
            health_profile=health_profile,
            longitude=longitude,
            latitude=latitude,
            knowledge_source=effective,
            scenario=scenario,
            selected_prescription_id=prescription_id,
        )
        async for output in self._agent_runner.astream_reply(messages, effort, context):
            projected = _project_agent_output(output, parts, self._disclaimer)
            if projected is not None:
                yield projected
        # 票 55：摘要不再阻塞 message 事件。emotion 仍同步（它是 message 负载的一部分，
        # 只阻塞一次 LLM）；摘要判定移到 done 之后的后台 task，避免第二次非流式 LLM
        # 调用（timeout 15s × 2 次校验重试）延迟 done 导致客户端输入框长时间锁死。
        message_data = await self._message_event_data(messages, parts, effort)
        yield {"event": EVENT_MESSAGE, "data": message_data}
        # 票 65：ambiguous 且候选非空时，文字回复后追加科室选择卡（点选经
        # retry_standard_department_id 直查号源）；卡片数据已由编排层确定性组装。
        if department_options:
            yield {
                "event": _GUIDED.options_card_event,
                "data": {
                    "standard_departments": department_options,
                    "disclaimer": self._disclaimer,
                },
            }
        yield {"event": EVENT_DONE, "data": {}}
        # 预问诊场景：done 已发出（客户端输入框解锁），后台异步整理摘要并回调 server-java。
        # fire-and-forget：不 await，task 内全 try/except 吞异常（摘要降级语义）。
        if scenario == _ONLINE.scenario and preconsultation_draft_id is not None:
            self._spawn_summary_task(
                preconsultation_draft_id, messages, "".join(parts), longitude, latitude
            )

    def _spawn_summary_task(
        self,
        draft_id: int,
        messages: list[dict[str, str]],
        assistant_text: str,
        longitude: float | None,
        latitude: float | None,
    ) -> None:
        """启动后台摘要判定 task：算出快照后回调 server-java 落草稿。

        task 在流结束（done 已 yield）之后创建，不阻塞 SSE 响应。回调 client 未装配
        （测试注入）或判定返回 None 时静默跳过。异常只 log.warning 不传播--摘要失败
        等同本轮降级，草稿保留上一版，不打断对话流。
        """

        async def _run() -> None:
            try:
                payload = await self._preconsult_summary(messages, assistant_text, longitude, latitude)
            except Exception:
                # _preconsult_summary 内部已有 try/except，此处兜底防未预期异常逃逸
                logger.warning("preconsult summary task failed draftId=%s", draft_id, exc_info=True)
                return
            if payload is None or self._summary_callback is None:
                return
            try:
                await self._summary_callback.apply(draft_id, payload)
            except Exception:
                # callback 内部已吞 httpx 错误，此处兜底防其它未预期异常
                logger.warning("preconsult summary callback failed draftId=%s", draft_id, exc_info=True)

        self._last_summary_task = asyncio.create_task(_run())

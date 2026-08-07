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
否则拉候选标准科室做科室解析（triage judge），收敛到单一科室
（explicit_booking/resolved）时强制查询。命中时先产出确定性摘要 message
事件、再产出 department_slots 卡片事件、最后 done，不进入 Agent 流；
未命中或目录不可用时退回正常 Agent 流。摘要措辞由代码按契约模板拼装，
LLM 不参与医院、医生、排班、余号等事实的生成。
"""

from collections.abc import AsyncIterator
from typing import Any

from app.agent.emotion import EmotionJudge, LazyEmotionJudge
from app.agent.runner import AgentContext, AgentRunner, HealthProfileContext
from app.agent.triage import LazyTriageJudge, TriageJudge
from app.core.contracts import get_contracts
from app.schemas.emotion import emotion_soothing_text
from app.services.directory import DepartmentDirectory
from app.services.reasoning import EffortChoice, Scenario, map_reasoning_effort

# SSE 流事件名唯一事实源是 contracts/sse-events.json；协议顺序固定，
# 由 tests/test_contract_consumption.py 与 java 侧 ContractsConsistencyTest 双端钉死。
EVENT_META, EVENT_KNOWLEDGE, EVENT_TOKEN, EVENT_MESSAGE, EVENT_DONE = (
    get_contracts().sse_events.stream_events
)

# 工具进度事件名（票 24）：tool_start/tool_end，不带免责声明（非 AI 产出）。
EVENT_TOOL_START, EVENT_TOOL_END = get_contracts().sse_events.trace_events

# 票 50：触发强制号源查询的解析状态为契约 resolution_statuses 前两态
# （explicit_booking/resolved），与契约的一致性由 tests/test_contract_consumption.py 钉死。
_GUIDED = get_contracts().guided_registration
_QUERY_STATUSES = frozenset(_GUIDED.resolution_statuses[:2])


def _earliest_key(guided: Any, earliest: dict[str, Any]) -> tuple[str, int]:
    """最早可约排序键：日期升序，同日按契约 time_slot_labels 顺序（AM 先于 PM）。"""
    order = list(guided.time_slot_labels)
    slot = earliest.get("time_slot", "")
    return (earliest.get("date", ""), order.index(slot) if slot in order else len(order))


def _build_slots_summary(payload: dict[str, Any]) -> str:
    """按契约 summary_templates 确定性拼装摘要（不让 LLM 生成号源事实）。

    ok：最早可约取所有 bookable 医生 earliest_bookable 的最小值，doctor_count 为
    bookable 医生数；无 bookable 医生（全部无号）用 empty 模板。
    """
    guided = _GUIDED
    department = payload.get("standard_department")
    name = department.get("name", "") if isinstance(department, dict) else ""
    doctors = [d for d in payload.get("doctors", []) if isinstance(d, dict)]
    bookable = [d for d in doctors if d.get("bookable")]
    earliest_pool = [d["earliest_bookable"] for d in bookable if isinstance(d.get("earliest_bookable"), dict)]
    if not bookable or not earliest_pool:
        return guided.summary_templates["empty"].format(department=name)
    earliest = min(earliest_pool, key=lambda e: _earliest_key(guided, e))
    slot = earliest.get("time_slot", "")
    return guided.summary_templates["ok"].format(
        department=name,
        earliest_date=earliest.get("date", ""),
        earliest_slot=guided.time_slot_labels.get(slot, slot),
        doctor_count=len(bookable),
    )


class AgentChatService:
    def __init__(
        self,
        agent_runner: AgentRunner,
        *,
        rag_available: bool = False,
        graph_available: bool = False,
        emotion_judge: EmotionJudge | None = None,
        triage_judge: TriageJudge | None = None,
        directory: DepartmentDirectory | None = None,
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
        longitude: float | None,
        latitude: float | None,
    ) -> int | None:
        """票 50：决定是否强制查询科室号源，返回标准科室 ID 或 None。

        重试字段非空 = 复用已确定科室直查（跳过目录拉取、解析与 Agent 流）；
        目录不可用/无候选/解析未收敛/ID 越界均返回 None，退回正常 Agent 流。
        """
        if self._directory is None:
            return None
        if retry_standard_department_id is not None:
            return retry_standard_department_id
        candidates = await self._directory.list_departments(longitude, latitude)
        if not isinstance(candidates, list) or not candidates:
            # 目录查询失败（错误文本）或无候选：跳过解析走正常 Agent 流
            return None
        resolution = await self._triage_judge.judge(messages, candidates)
        candidate_ids = {c["id"] for c in candidates}
        if (
            resolution.status in _QUERY_STATUSES
            and resolution.standard_department_id in candidate_ids
        ):
            return resolution.standard_department_id
        return None

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
    ) -> AsyncIterator[dict[str, object]]:
        """对一段消息历史流式生成回复。auto 在此映射为 disabled/high，不外传。"""
        effort = map_reasoning_effort(effort_choice, scenario)
        yield {"event": EVENT_META, "data": {"effort": effort}}

        # 票 50：编排层强制号源查询（不依赖 LLM 自主调工具）；命中即短路 Agent 流
        forced_id = await self._resolve_forced_department(
            messages, retry_standard_department_id, longitude, latitude
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
        )
        async for output in self._agent_runner.astream_reply(messages, effort, context):
            if output.event == EVENT_TOKEN and isinstance(output.data, str):
                parts.append(output.data)
                yield {"event": EVENT_TOKEN, "data": {"text": output.data}}
            elif output.event == EVENT_KNOWLEDGE and isinstance(output.data, dict):
                # runner 在 search_knowledge 成功检索时产出；不带免责声明（非 AI 产出）
                yield {"event": EVENT_KNOWLEDGE, "data": output.data}
            elif output.event in (EVENT_TOOL_START, EVENT_TOOL_END):
                # 工具进度事件（票 24）：无原文、无免责声明（非 AI 产出）。
                # tool_call_id 作为 start/end 配对键透传，duration_ms 由 server-java 墙钟计算。
                yield {"event": output.event, "data": output.data}
            elif isinstance(output.data, dict):
                yield {
                    "event": output.event,
                    "data": {**output.data, "disclaimer": self._disclaimer},
                }
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
        yield {"event": EVENT_MESSAGE, "data": message_data}
        yield {"event": EVENT_DONE, "data": {}}

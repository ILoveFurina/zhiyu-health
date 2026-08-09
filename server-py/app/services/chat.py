"""普通对话的总流程入口。

阅读 ``AgentChatService.stream`` 即可看到固定阶段：推理档位 → 确定性导诊/号源 →
知识增强选择 → Agent 流 → 最终消息/候选卡 → done → 非关键预问诊摘要。细节分别封装在
相邻的 ``chat_*`` 模块；本模块不生成号源事实、不写业务库，也不承担 LangGraph 投影。
"""

import asyncio
from collections.abc import AsyncIterator

from app.agent.emotion import EmotionJudge, LazyEmotionJudge
from app.agent.preconsult import LazyPreconsultJudge, PreconsultJudge
from app.agent.runner import AgentRunner
from app.agent.types import AgentContext, HealthProfileContext
from app.core.contracts import get_contracts
from app.services.chat_events import build_message_data, project_agent_output
from app.services.chat_guidance import GuidedRegistrationFlow
from app.services.chat_knowledge import KnowledgeSourcePolicy
from app.services.chat_preconsultation import PreconsultationSummaryScheduler
from app.services.department_tool_policy import DepartmentToolPolicy
from app.services.directory import DepartmentDirectory
from app.services.reasoning import EffortChoice, Scenario, map_reasoning_effort
from app.tools.preconsult_callback import SummaryCallback

_CONTRACTS = get_contracts()
EVENT_META, EVENT_KNOWLEDGE, EVENT_TOKEN, EVENT_MESSAGE, EVENT_DONE = (
    _CONTRACTS.sse_events.stream_events
)
_ONLINE = _CONTRACTS.online_consultation
_PRECONSULTATION_SCENARIO = _ONLINE.scenario
EVENT_THINKING = _CONTRACTS.chat_realtime.thinking_event


class AgentChatService:
    """协调一轮对话；构造参数都是已有生产/fake 两种适配器的真实 seam。"""

    def __init__(
        self,
        agent_runner: AgentRunner,
        *,
        rag_available: bool = False,
        graph_available: bool = False,
        emotion_judge: EmotionJudge | None = None,
        preconsult_judge: PreconsultJudge | None = None,
        directory: DepartmentDirectory | None = None,
        summary_callback: SummaryCallback | None = None,
    ) -> None:
        self._agent_runner = agent_runner
        self._emotion_judge = emotion_judge or LazyEmotionJudge()
        disclaimer = _CONTRACTS.disclaimer.text
        self._knowledge_policy = KnowledgeSourcePolicy(
            rag_available=rag_available, graph_available=graph_available
        )
        self._guidance = GuidedRegistrationFlow(directory, disclaimer)
        self._department_tool_policy = DepartmentToolPolicy(directory)
        self._preconsultation = PreconsultationSummaryScheduler(
            preconsult_judge or LazyPreconsultJudge(),
            directory,
            summary_callback,
            disclaimer,
        )
        self._disclaimer = disclaimer
        # 兼容存量的可确定等待测试；生产行为仍是不 await 的 fire-and-forget。
        self._last_summary_task: asyncio.Task[None] | None = None

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
        """流式执行一轮对话，并保持跨栈契约规定的事件形状与顺序。"""
        # 1. auto 只在编排层解释，传给模型和 meta 的始终是确定档位。
        effort = map_reasoning_effort(effort_choice, scenario)
        yield {"event": EVENT_META, "data": {"effort": effort}}

        # 2. 卡片点选/失败重试携带可信科室 ID，直接查询且不再进入 Agent。
        if retry_standard_department_id is not None:
            async for event in self._guidance.stream_slots(
                retry_standard_department_id, effort, longitude, latitude
            ):
                yield event
            return

        # 3. 明确挂号/导诊请求只读取可信目录，不增加独立 LLM judge；普通请求零目录调用。
        department_plan = await self._department_tool_policy.resolve(
            messages, scenario, longitude, latitude
        )

        # 4. 知识能力缺失是可观察降级，不阻断后续裸 LLM 回复。
        effective_source = self._knowledge_policy.resolve(knowledge_source, scenario)
        degraded = self._knowledge_policy.degraded_event(knowledge_source, scenario)
        if degraded is not None:
            yield {"event": EVENT_KNOWLEDGE, "data": degraded}

        # 5. runner 只执行 LangGraph；所有输出在一个投影点变成项目 SSE 事件。
        parts: list[str] = []
        context = AgentContext(
            patient_id=patient_id,
            conversation_id=conversation_id,
            health_profile=health_profile,
            longitude=longitude,
            latitude=latitude,
            knowledge_source=effective_source,
            scenario=scenario,
            selected_prescription_id=prescription_id,
            department_tool_choice=department_plan.tool_choice,
            standard_departments=department_plan.standard_departments,
        )
        async for output in self._agent_runner.astream_reply(messages, effort, context):
            projected = project_agent_output(output, parts, self._disclaimer)
            if projected is not None:
                yield projected

        # 6. emotion 必须进入最终 message；业务卡由工具结果在正文前投影，之后才 done。
        message_data = await build_message_data(
            messages, parts, effort, self._emotion_judge, self._disclaimer
        )
        yield {"event": EVENT_MESSAGE, "data": message_data}
        yield {"event": EVENT_DONE, "data": {}}

        # 7. 摘要不在关键路径：done 已让客户端解锁，失败只保留 server-java 上一版草稿。
        if scenario == _PRECONSULTATION_SCENARIO and preconsultation_draft_id is not None:
            self._last_summary_task = self._preconsultation.schedule(
                preconsultation_draft_id,
                messages,
                "".join(parts),
                longitude,
                latitude,
            )

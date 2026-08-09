"""智能导诊收敛与确定性科室号源卡流程。

LLM/judge 只帮助把症状收敛到契约中的标准科室；医院、医生、排班和余号事实始终来自
server-java。成功顺序固定为 message 摘要 → department_slots 卡 → done；查询失败仍出
可重试失败卡。预问诊不进入本流程，重复的 resolved 科室卡会被抑制。
"""

from collections.abc import AsyncIterator

from app.core.contracts import get_contracts
from app.tools.department import DepartmentDirectory, build_slots_summary

_CONTRACTS = get_contracts()
_GUIDED = _CONTRACTS.guided_registration
EVENT_MESSAGE = _CONTRACTS.sse_events.stream_events[3]
EVENT_DONE = _CONTRACTS.sse_events.stream_events[4]


_build_slots_summary = build_slots_summary


class GuidedRegistrationFlow:
    def __init__(
        self,
        directory: DepartmentDirectory | None,
        disclaimer: str,
    ) -> None:
        self._directory = directory
        self._disclaimer = disclaimer

    async def stream_slots(
        self,
        department_id: int,
        effort: str,
        longitude: float | None,
        latitude: float | None,
    ) -> AsyncIterator[dict[str, object]]:
        assert self._directory is not None
        result = await self._directory.get_slots(department_id, longitude, latitude)
        if isinstance(result, str):
            yield {
                "event": _GUIDED.card_event,
                "data": {
                    "status": _GUIDED.card_statuses[1],
                    "standard_department": {"id": department_id},
                    "message": _GUIDED.summary_templates["failed"],
                    "disclaimer": self._disclaimer,
                },
            }
            yield {"event": EVENT_DONE, "data": {}}
            return
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
            "event": _GUIDED.card_event,
            "data": {**result, "status": _GUIDED.card_statuses[0], "disclaimer": self._disclaimer},
        }
        yield {"event": EVENT_DONE, "data": {}}

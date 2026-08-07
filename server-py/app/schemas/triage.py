"""标准科室解析结构化输出契约（票 50）。

编排层在 Agent 流之前发起一次非流式 LLM 调用，判定对话是否已收敛到单一
标准科室，产出 TriageResolution(status, standard_department_id, rationale)；
rationale 仅调试用不下发。status 取值限定为契约四态
（contracts/guided-registration.json 的 resolution_statuses），
由 tests/test_contract_consumption.py 钉死一致性。
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class TriageResolution(BaseModel):
    """科室解析判定的结构化产物。

    explicit_booking=用户明确表达"科室+挂号"意图；resolved=多轮导诊收敛到
    单一明确标准科室；ambiguous=仍有多个可能科室；none=无科室线索。
    standard_department_id 仅前两态携带，且必须落在候选集内（越界由判定器
    降级 none）。失败/超时降级 none，不阻塞正常 Agent 流。
    """

    model_config = ConfigDict(extra="forbid")

    status: Literal["explicit_booking", "resolved", "ambiguous", "none"]
    standard_department_id: int | None = None
    rationale: str = Field(min_length=1)

    @classmethod
    def none_default(cls) -> "TriageResolution":
        """降级 none：科室解析失败/超时/越界的兜底产物（不触发强制查询）。"""
        return cls(status="none", rationale="降级：科室解析未成功")

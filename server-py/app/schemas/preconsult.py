"""预问诊病情摘要结构化输出契约（票 55）。

preconsultation 场景每个成功轮次结束后，编排层发起一次非流式 LLM 调用整理
病情摘要快照，产出 PreconsultationSummary；字段清单（主诉/现病史/过敏史）以
contracts/online-consultation.json 的 summary_fields 为单一事实源，
由 tests/test_contract_consumption.py 钉死一致性。快照挂在 message 事件下发，
server-java 据此更新预问诊草稿；摘要整理失败本轮省略快照字段，保留上一版。
"""

from pydantic import BaseModel, ConfigDict


class PreconsultationSummary(BaseModel):
    """预问诊摘要判定器的结构化产物。

    前三个字段在收集早期允许空字符串（信息未收齐），LLM 输出原样保留；
    suggested_standard_department_id 必须落在候选标准科室目录内，越界或
    未收敛时由判定器归一化为 None（不得由 LLM 自由编造目录外 ID）。
    """

    model_config = ConfigDict(extra="forbid")

    chief_complaint: str
    present_illness: str
    allergy_history: str
    suggested_standard_department_id: int | None = None

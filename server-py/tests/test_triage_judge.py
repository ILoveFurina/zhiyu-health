"""票 65：triage judge 的 ambiguous 候选科室归一化（_normalize / StructuredTriageJudge）。

候选 id 必须取自候选列表：越界丢弃、保序去重、截断到契约 options_max_candidates；
explicit_booking/resolved 不携带候选列表，standard_department_id 越界仍降级 none。
"""

import asyncio
import json

from app.agent.triage import StructuredTriageJudge, _normalize
from app.core.contracts import get_contracts
from app.schemas.triage import TriageResolution

_MAX = get_contracts().guided_registration.options_max_candidates
_CANDIDATES = {5, 8, 11, 14, 17}


def test_ambiguous_candidates_filtered_deduped_and_capped() -> None:
    """ambiguous：候选 id 过滤到候选集内（越界丢弃）、保序去重、截断到契约上限。"""
    resolution = TriageResolution(
        status="ambiguous",
        candidate_department_ids=[8, 999, 5, 8, 11, 14, 17],
        rationale="多个可能科室",
    )
    normalized = _normalize(resolution, _CANDIDATES)
    assert normalized.status == "ambiguous"
    # 999 越界丢弃、第二个 8 去重、保序后截断到上限
    assert normalized.candidate_department_ids == [8, 5, 11, 14, 17][: _MAX]
    assert normalized.standard_department_id is None


def test_explicit_and_resolved_drop_candidate_list() -> None:
    """explicit_booking/resolved：即使模型误输出候选列表也归一化为空。"""
    for status in ("explicit_booking", "resolved"):
        resolution = TriageResolution(
            status=status,  # type: ignore[arg-type]
            standard_department_id=5,
            candidate_department_ids=[8, 11],
            rationale="单一科室",
        )
        normalized = _normalize(resolution, _CANDIDATES)
        assert normalized.status == status
        assert normalized.standard_department_id == 5
        assert normalized.candidate_department_ids == []


def test_explicit_out_of_range_id_still_degrades_to_none() -> None:
    """explicit_booking 的 standard_department_id 越界：维持票 50 语义降级 none。"""
    resolution = TriageResolution(
        status="explicit_booking",
        standard_department_id=999,
        candidate_department_ids=[5],
        rationale="臆造科室",
    )
    normalized = _normalize(resolution, _CANDIDATES)
    assert normalized.status == "none"
    assert normalized.standard_department_id is None
    assert normalized.candidate_department_ids == []


class _FakeModel:
    """RawTriageModel fake：可编排原始文本输出序列，记录收到的 prompt。"""

    def __init__(self, outputs: list[str]) -> None:
        self._outputs = list(outputs)
        self.prompts: list[str] = []

    async def ainvoke(self, prompt_text: str) -> str:
        self.prompts.append(prompt_text)
        return self._outputs.pop(0)


def test_structured_judge_normalizes_ambiguous_candidates() -> None:
    """端到端（fake 模型）：合法 JSON 的 ambiguous 候选经归一化后下发。"""
    model = _FakeModel([
        json.dumps({
            "status": "ambiguous",
            "standard_department_id": None,
            "candidate_department_ids": [8, 999, 5],
            "rationale": "呼吸内科或皮肤科",
        })
    ])
    judge = StructuredTriageJudge(model)
    candidates = [{"id": 5, "name": "皮肤科"}, {"id": 8, "name": "呼吸内科"}]

    resolution = asyncio.run(judge.judge([{"role": "user", "content": "头痛发热"}], candidates))

    assert resolution.status == "ambiguous"
    assert resolution.candidate_department_ids == [8, 5]
    assert len(model.prompts) == 1

"""结构化预问诊 judge 的校验、重试和目录约束。"""

import asyncio
import json


from app.agent.preconsult import StructuredPreconsultJudge

_DEPARTMENTS = [
    {"id": 5, "name": "皮肤科", "category": "皮肤"},
    {"id": 8, "name": "呼吸内科", "category": "内科"},
]


class FakeRawPreconsultModel:
    """记录调用并按预设序列返回原始文本（可模拟非法 JSON / 校验失败 / 异常）。"""

    def __init__(self, responses: list[str | Exception]) -> None:
        self.responses = iter(responses)
        self.calls: list[str] = []

    async def ainvoke(self, prompt_text: str) -> str:
        self.calls.append(prompt_text)
        response = next(self.responses)
        if isinstance(response, Exception):
            raise response
        return response


_MESSAGES = [{"role": "user", "content": "我咳嗽三天了，青霉素过敏"}]


def test_judge_returns_summary_with_in_catalog_department() -> None:
    model = FakeRawPreconsultModel(
        [
            json.dumps(
                {
                    "chief_complaint": "咳嗽三天",
                    "present_illness": "干咳无痰",
                    "allergy_history": "青霉素",
                    "suggested_standard_department_id": 8,
                }
            )
        ]
    )
    judge = StructuredPreconsultJudge(model)

    result = asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS))

    assert result is not None
    assert result.chief_complaint == "咳嗽三天"
    assert result.suggested_standard_department_id == 8
    # prompt 携带候选目录与对话历史（受控解析的输入）
    assert "呼吸内科" in model.calls[0]
    assert "我咳嗽三天了，青霉素过敏" in model.calls[0]


def test_judge_normalizes_out_of_catalog_department_to_none() -> None:
    """目录外/臆造科室 ID：归一化为 None，摘要文本字段保留。"""
    model = FakeRawPreconsultModel(
        [
            json.dumps(
                {
                    "chief_complaint": "咳嗽三天",
                    "present_illness": "干咳无痰",
                    "allergy_history": "",
                    "suggested_standard_department_id": 999,
                }
            )
        ]
    )
    judge = StructuredPreconsultJudge(model)

    result = asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS))

    assert result is not None
    assert result.suggested_standard_department_id is None
    assert result.chief_complaint == "咳嗽三天"


def test_judge_forces_none_department_when_candidates_empty() -> None:
    """目录不可用（空候选）：任何建议科室都越界，强制 None，摘要本体仍保留。"""
    model = FakeRawPreconsultModel(
        [
            json.dumps(
                {
                    "chief_complaint": "咳嗽三天",
                    "present_illness": "干咳无痰",
                    "allergy_history": "",
                    "suggested_standard_department_id": 8,
                }
            )
        ]
    )
    judge = StructuredPreconsultJudge(model)

    result = asyncio.run(judge.judge(_MESSAGES, []))

    assert result is not None
    assert result.suggested_standard_department_id is None


def test_judge_retries_on_invalid_json_then_succeeds() -> None:
    model = FakeRawPreconsultModel(
        [
            "这不是 JSON",
            json.dumps(
                {
                    "chief_complaint": "咳嗽三天",
                    "present_illness": "干咳无痰",
                    "allergy_history": "",
                    "suggested_standard_department_id": None,
                }
            ),
        ]
    )
    judge = StructuredPreconsultJudge(model)

    result = asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS))

    assert result is not None
    assert result.chief_complaint == "咳嗽三天"
    assert len(model.calls) == 2


def test_judge_returns_none_after_two_validation_failures() -> None:
    model = FakeRawPreconsultModel(
        [
            json.dumps({"chief_complaint": "咳嗽三天"}),  # 缺字段：校验失败
            "仍然不是 JSON",
        ]
    )
    judge = StructuredPreconsultJudge(model)

    assert asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS)) is None
    assert len(model.calls) == 2


def test_judge_returns_none_on_model_exception() -> None:
    model = FakeRawPreconsultModel([RuntimeError("方舟调用超时")])
    judge = StructuredPreconsultJudge(model)

    assert asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS)) is None


def test_judge_rejects_extra_fields_in_llm_output() -> None:
    """extra=forbid：LLM 输出多字段两次校验失败后降级 None。"""
    payload = json.dumps(
        {
            "chief_complaint": "咳嗽三天",
            "present_illness": "干咳无痰",
            "allergy_history": "",
            "suggested_standard_department_id": None,
            "diagnosis": "上呼吸道感染",  # 越权字段：摘要判定器不做诊断
        }
    )
    model = FakeRawPreconsultModel([payload, payload])
    judge = StructuredPreconsultJudge(model)

    assert asyncio.run(judge.judge(_MESSAGES, _DEPARTMENTS)) is None
    assert len(model.calls) == 2

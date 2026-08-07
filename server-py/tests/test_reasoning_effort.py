"""推理档位映射测试（ADR-0015：普通对话关闭思考，复杂解读使用 high）。

票 54 起新增 preconsultation 场景：预问诊为多轮对话收集，速度优先，自动档关闭思考。
"""

import pytest

from app.services.reasoning import EffortChoice, Scenario, map_reasoning_effort


@pytest.mark.parametrize(
    ("choice", "scenario", "expected"),
    [
        ("quick", "triage", "disabled"),
        ("quick", "interpretation", "disabled"),
        ("quick", "preconsultation", "disabled"),
        ("deep", "triage", "high"),
        ("deep", "interpretation", "high"),
        ("deep", "preconsultation", "high"),
        ("auto", "triage", "disabled"),
        ("auto", "interpretation", "high"),
        ("auto", "preconsultation", "disabled"),
    ],
)
def test_mapping(choice: EffortChoice, scenario: Scenario, expected: str) -> None:
    assert map_reasoning_effort(choice, scenario) == expected


def test_auto_is_never_passed_through() -> None:
    for choice in ("auto", "quick", "deep"):
        for scenario in ("triage", "interpretation", "preconsultation"):
            assert map_reasoning_effort(choice, scenario) in ("disabled", "high")

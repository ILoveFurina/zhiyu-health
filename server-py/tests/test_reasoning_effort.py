"""推理档位映射测试（ADR-0013：普通对话关闭思考，复杂解读使用 high）。"""

import pytest

from app.services.reasoning import EffortChoice, Scenario, map_reasoning_effort


@pytest.mark.parametrize(
    ("choice", "scenario", "expected"),
    [
        ("quick", "triage", "disabled"),
        ("quick", "interpretation", "disabled"),
        ("deep", "triage", "high"),
        ("deep", "interpretation", "high"),
        ("auto", "triage", "disabled"),
        ("auto", "interpretation", "high"),
    ],
)
def test_mapping(choice: EffortChoice, scenario: Scenario, expected: str) -> None:
    assert map_reasoning_effort(choice, scenario) == expected


def test_auto_is_never_passed_through() -> None:
    for choice in ("auto", "quick", "deep"):
        for scenario in ("triage", "interpretation"):
            assert map_reasoning_effort(choice, scenario) in ("disabled", "high")

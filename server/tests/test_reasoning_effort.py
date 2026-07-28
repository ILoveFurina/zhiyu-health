"""推理档位映射测试（ADR-0004：自动档按场景分配，永不把 auto 传给模型）。"""

import pytest

from app.services.reasoning import EffortChoice, Scenario, map_reasoning_effort


@pytest.mark.parametrize(
    ("choice", "scenario", "expected"),
    [
        ("quick", "triage", "low"),
        ("quick", "interpretation", "low"),
        ("deep", "triage", "high"),
        ("deep", "interpretation", "high"),
        ("auto", "triage", "low"),
        ("auto", "interpretation", "high"),
    ],
)
def test_mapping(choice: EffortChoice, scenario: Scenario, expected: str) -> None:
    assert map_reasoning_effort(choice, scenario) == expected


def test_auto_is_never_passed_through() -> None:
    for choice in ("auto", "quick", "deep"):
        for scenario in ("triage", "interpretation"):
            assert map_reasoning_effort(choice, scenario) in ("low", "high")

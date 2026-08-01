"""推理档位映射（ADR-0013）。

C 端用户可选三档：自动 / 快速回答 / 深度思考。快速回答始终关闭思考，
深度思考始终使用 high；自动档普通对话关闭思考、复杂解读使用 high。
auto 永远不直接传给模型。
"""

from typing import Literal

EffortChoice = Literal["auto", "quick", "deep"]
Scenario = Literal["triage", "interpretation"]
ReasoningEffort = Literal["disabled", "high"]

# 自动档的场景分配表
_AUTO_BY_SCENARIO: dict[Scenario, ReasoningEffort] = {
    "triage": "disabled",
    "interpretation": "high",
}


def map_reasoning_effort(choice: EffortChoice, scenario: Scenario) -> ReasoningEffort:
    if choice == "quick":
        return "disabled"
    if choice == "deep":
        return "high"
    return _AUTO_BY_SCENARIO[scenario]

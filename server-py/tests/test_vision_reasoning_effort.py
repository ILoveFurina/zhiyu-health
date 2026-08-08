"""视觉场景推理档位（2026-08-08 起）：所有多模态视觉场景统一 disabled 提速。

决策依据：实测（.scratch/perf-vision-*）high 慢约 8 倍且输出稳定性差，
disabled 在结构化 JSON 抽取上不劣化且最快，故取消“其余场景保持 high”的旧约定。
"""

import asyncio

from app.agent.vision.document import PreparedDocument, PreparedPage
from app.agent.vision.interpreter import StructuredVisionInterpreter
from app.agent.vision.scenarios import POLICIES


def test_all_scenarios_disable_reasoning_effort() -> None:
    for scenario in POLICIES:
        assert POLICIES[scenario].reasoning_effort == "disabled"


class _RecordingRawModel:
    def __init__(self) -> None:
        self.efforts: list[str] = []

    async def ainvoke(
        self, content: list[dict[str, object]], system_prompt: str, reasoning_effort: str
    ) -> str:
        self.efforts.append(reasoning_effort)
        return '{"candidates":[{"name":"阿莫西林胶囊"}],"unreadable_hint":"","scope_supported":true}'


def test_interpreter_passes_policy_effort_to_model() -> None:
    model = _RecordingRawModel()
    document = PreparedDocument(
        scenario="PILL_BOX",
        pages=(PreparedPage(number=1, mode="image", image=b"x", media_type="image/jpeg"),),
    )
    asyncio.run(StructuredVisionInterpreter(model).interpret(document))
    assert model.efforts == ["disabled"]

"""视觉场景推理档位（票 51）：PILL_BOX 纯 OCR 提名关闭思考提速，其余场景保持 high。"""

import asyncio

from app.agent.vision.document import PreparedDocument, PreparedPage
from app.agent.vision.interpreter import StructuredVisionInterpreter
from app.agent.vision.scenarios import POLICIES


def test_pill_box_disables_reasoning_effort() -> None:
    assert POLICIES["PILL_BOX"].reasoning_effort == "disabled"


def test_other_scenarios_keep_high_effort() -> None:
    for scenario in ("REPORT", "SKIN", "DIET", "TONGUE"):
        assert POLICIES[scenario].reasoning_effort == "high"


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

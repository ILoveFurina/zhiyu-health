"""视觉接口测试共用 fake 与最小文件样例。"""

from io import BytesIO

import pymupdf
from PIL import Image

from app.schemas.vision import ReportInterpretation


class FakeVisionInterpreter:
    def __init__(self) -> None:
        self.calls: list[object] = []

    async def interpret(self, document: object) -> ReportInterpretation:
        self.calls.append(document)
        return ReportInterpretation.model_validate(
            {
                "summary": "血常规中血红蛋白偏低，建议结合症状咨询医生。",
                "items": [
                    {
                        "name": "血红蛋白",
                        "value": "108",
                        "reference_range": "115-150",
                        "unit": "g/L",
                        "priority": "yellow",
                        "explanation": "低于报告参考范围。",
                        "action": "建议按医嘱复查血常规。",
                        "page": 1,
                    }
                ],
                "actions": ["携带报告咨询医生"],
                "unreadable": [],
                "scope_supported": True,
            }
        )


class FakeRawVisionModel:
    def __init__(self, responses: list[str]) -> None:
        self.responses = iter(responses)
        self.calls: list[list[dict[str, object]]] = []
        self.system_prompts: list[str] = []

    async def ainvoke(
        self, content: list[dict[str, object]], system_prompt: str, reasoning_effort: str
    ) -> str:
        self.calls.append(content)
        self.system_prompts.append(system_prompt)
        return next(self.responses)


def _png() -> bytes:
    output = BytesIO()
    Image.new("RGB", (40, 30), "white").save(output, format="PNG")
    return output.getvalue()


def _webp() -> bytes:
    # 支付宝小程序 my.uploadFile 默认把图片压缩转码为 webp，回归必须覆盖该格式。
    output = BytesIO()
    Image.new("RGB", (40, 30), "white").save(output, format="WEBP")
    return output.getvalue()


def _large_png() -> bytes:
    output = BytesIO()
    Image.new("RGB", (3000, 120), "white").save(output, format="PNG")
    return output.getvalue()


def _mixed_pdf() -> bytes:
    document = pymupdf.open()
    text_page = document.new_page()
    text_page.insert_text(
        (72, 72),
        "Laboratory report narrative with enough readable characters for direct extraction.",
    )

    table_page = document.new_page()
    table_page.insert_text(
        (72, 72),
        "Blood test table Hemoglobin 108 reference 115-150 and Platelets 210 reference 100-300.",
    )
    for offset in range(4):
        table_page.draw_line((72, 100 + offset * 24), (450, 100 + offset * 24))
    for offset in range(4):
        table_page.draw_line((72 + offset * 126, 100), (72 + offset * 126, 172))

    scan_page = document.new_page()
    scan_page.insert_image(scan_page.rect, stream=_png())
    content = document.tobytes()
    document.close()
    return content


def _blank_pdf() -> bytes:
    document = pymupdf.open()
    document.new_page()
    content = document.tobytes()
    document.close()
    return content

"""视觉输入校验与规范化。"""

import re
from dataclasses import dataclass
from io import BytesIO
from typing import Literal

import pymupdf
from fastapi import UploadFile
from PIL import Image, UnidentifiedImageError

MAX_FILE_BYTES = 10 * 1024 * 1024
MAX_IMAGE_TOTAL_BYTES = 20 * 1024 * 1024
MAX_PDF_PAGES = 20
MAX_IMAGE_EDGE = 2048
MAX_IMAGE_PIXELS = 40_000_000
_MIN_TEXT_CHARS = 30
_NORMAL_CHAR_RATIO = 0.90
_PII_PATTERNS = (
    re.compile(r"(?i)(姓名\s*[:：]?\s*)[^\s,，;；]{2,8}"),
    re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"),
    re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)"),
    re.compile(r"(?i)((?:就诊卡号|病案号|报告编号)\s*[:：]?\s*)[A-Za-z0-9-]{4,}"),
)


class VisionInputError(ValueError):
    def __init__(self, code: str, detail: str) -> None:
        super().__init__(detail)
        self.code = code


@dataclass(frozen=True)
class PreparedPage:
    number: int
    mode: Literal["image", "text", "text_image"]
    text: str | None = None
    image: bytes | None = None
    media_type: str | None = None


@dataclass(frozen=True)
class PreparedDocument:
    scenario: Literal["REPORT"]
    pages: tuple[PreparedPage, ...]

    @property
    def page_count(self) -> int:
        return len(self.pages)


async def prepare_document(files: list[UploadFile], scenario: str) -> PreparedDocument:
    if scenario != "REPORT":
        raise VisionInputError("VISION_SCENARIO_UNSUPPORTED", "暂不支持该视觉场景")
    if not 1 <= len(files) <= 5:
        raise VisionInputError("VISION_FILE_COUNT_INVALID", "请选择 1 至 5 张报告图片")

    if any(upload.content_type == "application/pdf" for upload in files):
        if len(files) != 1:
            raise VisionInputError("VISION_FILE_COUNT_INVALID", "PDF 每次只能上传一个文件")
        data = await files[0].read()
        return _prepare_pdf(data)

    pages: list[PreparedPage] = []
    total_bytes = 0
    for number, upload in enumerate(files, start=1):
        data = await upload.read()
        total_bytes += len(data)
        if len(data) > MAX_FILE_BYTES:
            raise VisionInputError("VISION_FILE_TOO_LARGE", "单张报告图片不能超过 10MB")
        if total_bytes > MAX_IMAGE_TOTAL_BYTES:
            raise VisionInputError("VISION_FILE_TOO_LARGE", "报告图片总量不能超过 20MB")
        if upload.content_type not in {"image/jpeg", "image/png"}:
            raise VisionInputError("VISION_FILE_TYPE_INVALID", "仅支持 JPEG、PNG 或 PDF")
        normalized = _normalize_image(data)
        pages.append(
            PreparedPage(
                number=number,
                mode="image",
                image=normalized,
                media_type="image/jpeg",
            )
        )
    return PreparedDocument(scenario="REPORT", pages=tuple(pages))


def _prepare_pdf(data: bytes) -> PreparedDocument:
    if len(data) > MAX_FILE_BYTES:
        raise VisionInputError("VISION_FILE_TOO_LARGE", "PDF 不能超过 10MB")
    try:
        document = pymupdf.open(stream=data, filetype="pdf")
    except (pymupdf.FileDataError, RuntimeError) as exc:
        raise VisionInputError("VISION_FILE_UNREADABLE", "PDF 无法读取") from exc
    try:
        if document.needs_pass:
            raise VisionInputError("VISION_PDF_ENCRYPTED", "不支持加密 PDF")
        if not 1 <= document.page_count <= MAX_PDF_PAGES:
            raise VisionInputError("VISION_PDF_PAGE_LIMIT", "PDF 须为 1 至 20 页")
        if not any(
            _pdf_page_has_content(document.load_page(index))
            for index in range(document.page_count)
        ):
            raise VisionInputError("VISION_FILE_UNREADABLE", "PDF 内容为空")
        pages = tuple(
            _prepare_pdf_page(document.load_page(index), index + 1)
            for index in range(document.page_count)
        )
        return PreparedDocument(scenario="REPORT", pages=pages)
    finally:
        document.close()


def _pdf_page_has_content(page: pymupdf.Page) -> bool:
    return bool(page.get_text("text").strip() or page.get_images(full=True) or page.get_drawings())


def _prepare_pdf_page(page: pymupdf.Page, number: int) -> PreparedPage:
    text = _redact_pii(page.get_text("text", sort=True).strip())
    reliable = _is_reliable_text(text)
    layout_risk = bool(page.get_images(full=True)) or len(page.get_drawings()) >= 4
    if reliable and not layout_risk:
        return PreparedPage(number=number, mode="text", text=text)

    image = _render_page(page)
    if reliable:
        return PreparedPage(
            number=number,
            mode="text_image",
            text=text,
            image=image,
            media_type="image/jpeg",
        )
    return PreparedPage(number=number, mode="image", image=image, media_type="image/jpeg")


def _is_reliable_text(text: str) -> bool:
    compact = "".join(char for char in text if not char.isspace())
    if len(compact) < _MIN_TEXT_CHARS:
        return False
    normal = sum(
        char.isalnum() or "\u4e00" <= char <= "\u9fff" or char in ",.;:!?%+-/()[]，。；：！？（）【】"
        for char in compact
    )
    return normal / len(compact) >= _NORMAL_CHAR_RATIO and "�" not in compact


def _render_page(page: pymupdf.Page) -> bytes:
    pixmap = page.get_pixmap(matrix=pymupdf.Matrix(1.5, 1.5), alpha=False)
    return pixmap.tobytes("jpeg", jpg_quality=85)


def _redact_pii(text: str) -> str:
    redacted = text
    for pattern in _PII_PATTERNS:
        if pattern.groups:
            redacted = pattern.sub(r"\1[已遮盖]", redacted)
        else:
            redacted = pattern.sub("[已遮盖]", redacted)
    return redacted


def _normalize_image(data: bytes) -> bytes:
    try:
        with Image.open(BytesIO(data)) as image:
            if image.width * image.height > MAX_IMAGE_PIXELS:
                raise VisionInputError("VISION_IMAGE_PIXELS_EXCEEDED", "报告图片像素过大")
            normalized = image.convert("RGB")
            normalized.thumbnail((MAX_IMAGE_EDGE, MAX_IMAGE_EDGE), Image.Resampling.LANCZOS)
            output = BytesIO()
            normalized.save(output, format="JPEG", quality=85, optimize=True)
            return output.getvalue()
    except VisionInputError:
        raise
    except (UnidentifiedImageError, OSError) as exc:
        raise VisionInputError("VISION_FILE_UNREADABLE", "报告图片无法读取") from exc

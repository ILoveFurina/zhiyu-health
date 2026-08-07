"""视觉输入校验与规范化。"""

import re
from dataclasses import dataclass
from io import BytesIO
from typing import Literal

import pymupdf
from fastapi import UploadFile
from PIL import Image, UnidentifiedImageError
from pydantic import ValidationError

from app.agent.vision.scenarios import POLICIES, policy_for
from app.core.contracts import get_contracts
from app.schemas.chat import HealthProfilePayload

# 上传限制唯一事实源是 contracts/upload-limits.json（两端入口校验必须一致，装配期取值缓存）
_LIMITS = get_contracts().upload_limits
MAX_FILE_BYTES = _LIMITS.max_file_bytes
MAX_IMAGE_TOTAL_BYTES = _LIMITS.max_total_bytes
_MIN_FILES = _LIMITS.min_files
_MAX_FILES = _LIMITS.max_files
# 允许类型中的图片子集；PDF 走独立分支，不进图片校验
_IMAGE_TYPES = frozenset(t for t in _LIMITS.allowed_types if t.startswith("image/"))
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


def _input_error(code: str) -> VisionInputError:
    """按契约错误码构造输入错误；用户文案以 vision-errors.json（java 出口版）为准。"""
    messages = get_contracts().vision_errors.messages
    if code not in messages:
        raise RuntimeError(f"错误码未在 contracts/vision-errors.json 登记: {code}")
    return VisionInputError(code, messages[code])


def parse_optional_health_profile(raw: str | None) -> HealthProfilePayload | None:
    """可选健康档案解析（票 46）：无档案是合法业务状态，契约上只用"字段未传"表达；
    历史调用方曾把空档案序列化为字面 "null" 发出，兼容视为未提供。
    其余畸形/不完整 JSON 契约化为 VISION_PROFILE_INVALID，不得泄漏为裸 500。"""
    if raw is None or raw.strip() == "null":
        return None
    try:
        return HealthProfilePayload.model_validate_json(raw)
    except ValidationError as exc:
        raise _input_error("VISION_PROFILE_INVALID") from exc


@dataclass(frozen=True)
class PreparedPage:
    number: int
    mode: Literal["image", "text", "text_image"]
    text: str | None = None
    image: bytes | None = None
    media_type: str | None = None


@dataclass(frozen=True)
class PreparedDocument:
    # scenario 驱动分发：REPORT 走 PDF/多页文本，拍照分析场景（SKIN 等）只走图片分支。
    scenario: str
    pages: tuple[PreparedPage, ...]
    health_profile: HealthProfilePayload | None = None

    @property
    def page_count(self) -> int:
        return len(self.pages)


async def prepare_document(files: list[UploadFile], scenario: str) -> PreparedDocument:
    # 场景白名单：未注册策略一律拒绝，防止 C 端注入任意场景标识。
    if scenario not in POLICIES:
        raise _input_error("VISION_SCENARIO_UNSUPPORTED")
    policy = policy_for(scenario)
    if not _MIN_FILES <= len(files) <= _MAX_FILES:
        raise _input_error("VISION_FILE_COUNT_INVALID")

    # PDF 仅 report 场景支持；拍照分析场景收到 PDF 直接拒绝（皮肤/饮食/舌苔为图片场景）。
    if any(upload.content_type == "application/pdf" for upload in files):
        if not policy.supports_pdf or len(files) != 1:
            raise _input_error("VISION_FILE_COUNT_INVALID")
        data = await files[0].read()
        return _prepare_pdf(data, scenario)

    pages: list[PreparedPage] = []
    total_bytes = 0
    for number, upload in enumerate(files, start=1):
        data = await upload.read()
        total_bytes += len(data)
        if len(data) > MAX_FILE_BYTES:
            raise _input_error("VISION_FILE_TOO_LARGE")
        if total_bytes > MAX_IMAGE_TOTAL_BYTES:
            raise _input_error("VISION_FILE_TOO_LARGE")
        if upload.content_type not in _IMAGE_TYPES and _detect_image_kind(data) is None:
            raise _input_error("VISION_FILE_TYPE_INVALID")
        normalized = _normalize_image(data)
        pages.append(
            PreparedPage(
                number=number,
                mode="image",
                image=normalized,
                media_type="image/jpeg",
            )
        )
    return PreparedDocument(scenario=scenario, pages=tuple(pages))


def _prepare_pdf(data: bytes, scenario: str) -> PreparedDocument:
    if len(data) > MAX_FILE_BYTES:
        raise _input_error("VISION_FILE_TOO_LARGE")
    try:
        document = pymupdf.open(stream=data, filetype="pdf")
    except (pymupdf.FileDataError, RuntimeError) as exc:
        raise _input_error("VISION_FILE_UNREADABLE") from exc
    try:
        if document.needs_pass:
            raise _input_error("VISION_PDF_ENCRYPTED")
        if not 1 <= document.page_count <= MAX_PDF_PAGES:
            raise _input_error("VISION_PDF_PAGE_LIMIT")
        if not any(
            _pdf_page_has_content(document.load_page(index))
            for index in range(document.page_count)
        ):
            raise _input_error("VISION_FILE_UNREADABLE")
        pages = tuple(
            _prepare_pdf_page(document.load_page(index), index + 1)
            for index in range(document.page_count)
        )
        return PreparedDocument(scenario=scenario, pages=pages)
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


def _detect_image_kind(data: bytes) -> str | None:
    """按字节 magic bytes 探测图片格式（不信任客户端声明的 content-type）。

    支付宝 my.uploadFile 不会可靠地为文件 part 设置 Content-Type（常为
    application/octet-stream 或空），且默认会把图片压缩转码为 image/webp，故当客户端
    content-type 不在白名单时回退到字节探测：命中 JPEG/PNG/WEBP 视为合法图片，其余返回
    None（由调用方抛 VISION_FILE_TYPE_INVALID）。
    """
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    # WEBP: "RIFF"...."WEBP"（偏移 0-3 为 RIFF，偏移 8-11 为 WEBP）
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def _normalize_image(data: bytes) -> bytes:
    try:
        with Image.open(BytesIO(data)) as image:
            if image.width * image.height > MAX_IMAGE_PIXELS:
                raise _input_error("VISION_IMAGE_PIXELS_EXCEEDED")
            normalized = image.convert("RGB")
            normalized.thumbnail((MAX_IMAGE_EDGE, MAX_IMAGE_EDGE), Image.Resampling.LANCZOS)
            output = BytesIO()
            normalized.save(output, format="JPEG", quality=85, optimize=True)
            return output.getvalue()
    except VisionInputError:
        raise
    except (UnidentifiedImageError, OSError) as exc:
        raise _input_error("VISION_FILE_UNREADABLE") from exc

"""跨栈契约基座：仓库根 contracts/ 是 server-java 与 server-py 共享常量的单一事实源。

懒加载 + 进程内缓存；契约缺失或损坏属部署错误，首次访问即抛 RuntimeError，
不静默降级。消费接线在后续阶段进行，当前只暴露 get_contracts() 供读取。
"""

import json
import os
from functools import lru_cache
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field, ValidationError

# server-py/app/core/contracts.py → parents[3] 即仓库根
_DEFAULT_DIR = Path(__file__).resolve().parents[3] / "contracts"


class DisclaimerContract(BaseModel):
    """免责声明标注：一切 AI 产出必须携带（硬约束 1）。"""

    text: str


class SseEventsContract(BaseModel):
    """SSE 事件协议：流事件名、工具名→事件名、消息 kind 与事件→kind 映射。"""

    stream_events: list[str]
    red_flag_event: str
    card_events: list[str]
    trace_events: list[str]
    trace_results: list[str]
    trace_error_code_unknown: str
    tool_to_event: dict[str, str]
    message_kinds: list[str]
    ai_card_kinds: list[str]
    event_to_kind: dict[str, str]


class VisionErrorsContract(BaseModel):
    """报告解读错误码集合与用户可见文案（文案以 server-java 出口为准）。"""

    codes: list[str]
    messages: dict[str, str]


class UploadLimitsContract(BaseModel):
    """报告上传限制：两端入口校验必须一致。"""

    max_file_bytes: int
    max_total_bytes: int
    min_files: int
    max_files: int
    allowed_types: list[str]
    pdf_single_file: bool


class ChatDefaultsContract(BaseModel):
    """对话默认值与经纬度校验范围。"""

    effort_default: str
    scenario_default: str
    effort_choices: list[str]
    scenarios: list[str]
    longitude_min: float
    longitude_max: float
    latitude_min: float
    latitude_max: float


class PrescriptionFlowContract(BaseModel):
    statuses: dict[str, str]
    status_labels: dict[str, str]
    decisions: dict[str, str]
    message_types: dict[str, str]


class PaymentFlowContract(BaseModel):
    statuses: dict[str, str]
    status_labels: dict[str, str]
    decisions: dict[str, str]
    messages: dict[str, str]


class ContraindicationContract(BaseModel):
    decisions: dict[str, str]
    message_types: dict[str, str]
    messages: dict[str, str]
    advice: str


class KnowledgeContract(BaseModel):
    """知识增强模式：知识源二态 rag/graph + 自动降级、向量检索参数（ADR-0010）。"""

    knowledge_sources: list[str]
    none_source: str
    default_by_scenario: dict[str, str]
    knowledge_meta_event: str
    knowledge_status: list[str]
    embedding_dimension: int
    vector_column: str
    search_top_k: int
    similarity_threshold: float


class EmotionContract(BaseModel):
    """情绪反馈（票 44，ADR-0019）：三档情绪标注 + 默认值 + 安抚语映射。

    emotion 挂 message 事件下发（_carried_by=message），枚举、默认值与安抚语
    在此契约单一事实源；calm 无安抚语（映射缺省即无），anxious/fearful 各一条。
    """

    emotions: list[str]
    default: str
    # JSON 键是 _carried_by（下划线前缀说明性字段命名约定），用 alias 对齐。
    carried_by: str = Field(alias="_carried_by")
    soothing_texts: dict[str, str]


class Contracts(BaseModel):
    disclaimer: DisclaimerContract
    sse_events: SseEventsContract
    vision_errors: VisionErrorsContract
    upload_limits: UploadLimitsContract
    chat_defaults: ChatDefaultsContract
    prescription_flow: PrescriptionFlowContract
    payment_flow: PaymentFlowContract
    contraindication: ContraindicationContract
    knowledge: KnowledgeContract
    emotion: EmotionContract


def _contracts_dir() -> Path:
    # 环境变量 CONTRACTS_DIR 优先，默认仓库根 contracts/
    override = os.environ.get("CONTRACTS_DIR")
    return Path(override) if override else _DEFAULT_DIR


def _read_json(dir_path: Path, name: str) -> dict[str, Any]:
    file = dir_path / name
    try:
        data = json.loads(file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(
            f"跨栈契约加载失败（检查 contracts/ 是否随仓库完整部署）: {file} ({exc})"
        ) from exc
    if not isinstance(data, dict):
        raise TypeError(f"跨栈契约必须是 JSON 对象: {file}")
    return data


def _load(dir_path: Path) -> Contracts:
    try:
        return Contracts(
            disclaimer=DisclaimerContract.model_validate(_read_json(dir_path, "disclaimer.json")),
            sse_events=SseEventsContract.model_validate(_read_json(dir_path, "sse-events.json")),
            vision_errors=VisionErrorsContract.model_validate(
                _read_json(dir_path, "vision-errors.json")
            ),
            upload_limits=UploadLimitsContract.model_validate(
                _read_json(dir_path, "upload-limits.json")
            ),
            chat_defaults=ChatDefaultsContract.model_validate(
                _read_json(dir_path, "chat-defaults.json")
            ),
            prescription_flow=PrescriptionFlowContract.model_validate(
                _read_json(dir_path, "prescription-flow.json")
            ),
            payment_flow=PaymentFlowContract.model_validate(
                _read_json(dir_path, "payment-flow.json")
            ),
            contraindication=ContraindicationContract.model_validate(
                _read_json(dir_path, "contraindication.json")
            ),
            knowledge=KnowledgeContract.model_validate(_read_json(dir_path, "knowledge.json")),
            emotion=EmotionContract.model_validate(_read_json(dir_path, "emotion.json")),
        )
    except ValidationError as exc:
        raise RuntimeError(f"跨栈契约结构校验失败（需双栈同步检查 contracts/）: {exc}") from exc


@lru_cache
def get_contracts() -> Contracts:
    """进程内单例：首次访问时加载并缓存，失败抛 RuntimeError。"""
    return _load(_contracts_dir())

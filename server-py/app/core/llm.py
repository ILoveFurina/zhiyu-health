"""LLM 客户端装配：火山方舟 ChatOpenAI 的唯一构建点（ADR-0004）。

对话（agent.runner）与报告解读（agent.vision）共用此处装配，
差异项（推理档位、超时、重试次数）以参数传入。
"""

from typing import Any, Literal

from langchain_openai import ChatOpenAI
from pydantic import SecretStr

from app.config import Settings


def build_chat_model(
    settings: Settings,
    *,
    reasoning_effort: Literal["disabled", "low", "high"],
    timeout: float | None = None,
    max_retries: int | None = None,
) -> ChatOpenAI:
    # timeout/max_retries 显式传 None 会覆盖 langchain 默认值，故仅在调用方给出时透传
    options: dict[str, Any] = {}
    if timeout is not None:
        options["timeout"] = timeout
    if max_retries is not None:
        options["max_retries"] = max_retries
    if reasoning_effort == "disabled":
        # 方舟 OpenAI 兼容接口的非标准 thinking 字段必须经 extra_body 透传。
        options["extra_body"] = {"thinking": {"type": "disabled"}}
    else:
        options["reasoning_effort"] = reasoning_effort
    return ChatOpenAI(
        model=settings.doubao_chat_model,
        base_url=settings.ark_base_url,
        api_key=SecretStr(settings.ark_api_key),
        **options,
    )

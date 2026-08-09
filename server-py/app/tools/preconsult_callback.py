"""预问诊摘要的非关键业务回调。

摘要在 ``done`` 后异步生成；回调失败只意味着草稿保留上一版，不能反向影响已完成的
对话流。日志只记录草稿 ID 和异常类型，不记录患者病情摘要原文。
"""

import logging
from typing import Any, Protocol

import httpx

from app.tools.callback import BusinessCallbackClient

logger = logging.getLogger("app.tools.preconsult_callback")


class SummaryCallback(Protocol):
    async def apply(self, draft_id: int, payload: dict[str, Any]) -> None: ...


class PreconsultationSummaryCallback:
    def __init__(self, client: BusinessCallbackClient) -> None:
        self._client = client

    async def apply(self, draft_id: int, payload: dict[str, Any]) -> None:
        try:
            await self._client.post(
                f"/api/agent/preconsultation-drafts/{draft_id}/summary", payload
            )
        except httpx.HTTPError as error:
            logger.warning(
                "preconsultation summary callback failed draftId=%s error=%s",
                draft_id,
                error.__class__.__name__,
            )

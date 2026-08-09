"""预问诊摘要的后台一致性流程。

主回复和 ``done`` 必须先交付，之后才整理结构化摘要并回调 server-java。目录或 judge
失败、超时、回调失败都只保留上一版草稿；日志不包含患者对话与摘要原文。
"""

import asyncio
import logging
from typing import Any

from app.agent.preconsult import PreconsultJudge
from app.services.directory import DepartmentDirectory
from app.tools.preconsult_callback import SummaryCallback

logger = logging.getLogger("app.services.chat_preconsultation")


class PreconsultationSummaryScheduler:
    def __init__(
        self,
        judge: PreconsultJudge,
        directory: DepartmentDirectory | None,
        callback: SummaryCallback | None,
        disclaimer: str,
    ) -> None:
        self._judge = judge
        self._directory = directory
        self._callback = callback
        self._disclaimer = disclaimer
        self.last_task: asyncio.Task[None] | None = None

    async def _build(
        self,
        messages: list[dict[str, str]],
        assistant_text: str,
        longitude: float | None,
        latitude: float | None,
    ) -> dict[str, object] | None:
        try:
            candidates: list[dict[str, Any]] = []
            if self._directory is not None:
                listed = await self._directory.list_departments(longitude, latitude)
                if isinstance(listed, list):
                    candidates = listed
            round_messages = [*messages, {"role": "assistant", "content": assistant_text}]
            summary = await self._judge.judge(round_messages, candidates)
        except Exception:
            return None
        if summary is None:
            return None
        # 摘要是 AI 产出，即使仅供 server-java 草稿轮询也必须携带统一免责声明。
        return {**summary.model_dump(), "disclaimer": self._disclaimer}

    def schedule(
        self,
        draft_id: int,
        messages: list[dict[str, str]],
        assistant_text: str,
        longitude: float | None,
        latitude: float | None,
    ) -> asyncio.Task[None]:
        async def run() -> None:
            try:
                payload = await self._build(messages, assistant_text, longitude, latitude)
                if payload is not None and self._callback is not None:
                    await self._callback.apply(draft_id, payload)
            except Exception as error:
                # 回调异常可能携带下游响应或摘要片段；只记录类型，不输出消息和 traceback。
                logger.warning(
                    "preconsult summary task failed draftId=%s error=%s",
                    draft_id,
                    error.__class__.__name__,
                )

        self.last_task = asyncio.create_task(run())
        return self.last_task

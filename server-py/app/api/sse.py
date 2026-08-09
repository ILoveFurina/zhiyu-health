"""SSE 出口：帧拼装与流生命周期日志（server-py 侧唯一 SSE 拼装点）。

SSE 链路跨双栈多跳（端 → server-java 中继 → 本端 → LLM/工具回调），排查
证明每一跳都必须能回答"流走到哪、在哪断"：本端曾无任何日志，无法区分上游
取消与本端故障。只记录身份、档位、事件名、帧字节数与计数，不记患者原文（硬规则 5）。
"""

import asyncio
import json
import logging
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass

logger = logging.getLogger("app.api.sse")


@dataclass(frozen=True)
class SseStreamContext:
    """单条 SSE 流的可观测上下文：只含身份与档位，不含消息原文。"""

    conversation_id: int
    patient_id: int
    effort: str
    scenario: str


def sse_frame(event: str, data: object) -> str:
    # 帧格式是跨栈契约（server-java 按 event:/data: 行序解析），由 tests/test_sse_logging.py 钉死
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n"


async def log_sse_stream(
    events: AsyncIterator[dict[str, object]], *, context: SseStreamContext
) -> AsyncIterator[str]:
    """把上游事件流逐帧编码为 SSE 文本，并输出 start/complete/cancel/error 四级日志。

    取消与异常只留痕、不改变语义：CancelledError 与异常原样上抛，交由 uvicorn
    结束响应；流被上游（server-java 中继）取消时必须留下 WARNING，否则断流方向不可辨。
    """
    started = time.monotonic()
    counts: dict[str, int] = {}
    logger.info(
        "sse stream start conversation=%s patient=%s effort=%s scenario=%s",
        context.conversation_id,
        context.patient_id,
        context.effort,
        context.scenario,
    )
    try:
        async for event in events:
            name = str(event["event"])
            counts[name] = counts.get(name, 0) + 1
            frame = sse_frame(name, event["data"])
            logger.debug(
                "sse frame conversation=%s event=%s bytes=%d",
                context.conversation_id,
                name,
                len(frame.encode("utf-8")),
            )
            yield frame
    except asyncio.CancelledError:
        logger.warning(
            "sse stream cancelled by client conversation=%s events=%s elapsedMs=%d",
            context.conversation_id,
            counts,
            _elapsed_ms(started),
        )
        raise
    except Exception:
        logger.exception(
            "sse stream failed conversation=%s events=%s elapsedMs=%d",
            context.conversation_id,
            counts,
            _elapsed_ms(started),
        )
        raise
    logger.info(
        "sse stream complete conversation=%s events=%s elapsedMs=%d",
        context.conversation_id,
        counts,
        _elapsed_ms(started),
    )


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)

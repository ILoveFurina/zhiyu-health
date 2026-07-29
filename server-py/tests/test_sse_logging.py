"""SSE 出口日志（票 33）：流生命周期必须留痕——断流时能定位流走到哪、在哪断。

链路跨双栈多跳，票 33 排查时 server-py 侧"无任何日志"，无法区分上游取消与
本端故障；本模块的 start/complete/cancel/error 四级日志是断流定位的最小信息集。
"""

import asyncio
import logging
from collections.abc import AsyncIterator

import pytest

from app.api.sse import SseStreamContext, log_sse_stream, sse_frame

CONTEXT = SseStreamContext(conversation_id=7, patient_id=12, effort="low", scenario="triage")


async def _events(*items: dict[str, object]) -> AsyncIterator[dict[str, object]]:
    for item in items:
        yield item


def _drain(stream: AsyncIterator[str]) -> list[str]:
    async def run() -> list[str]:
        return [frame async for frame in stream]

    return asyncio.run(run())


def test_sse_frame_format_is_unchanged() -> None:
    # 帧格式是跨栈契约（server-java 按 event:/data: 行序解析），只许搬运不许改形
    assert sse_frame("token", {"text": "你好"}) == 'event: token\ndata: {"text": "你好"}\n\n'


def test_complete_stream_yields_frames_and_logs_lifecycle(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level(logging.DEBUG, logger="app.api.sse"):
        frames = _drain(log_sse_stream(
            _events(
                {"event": "meta", "data": {"effort": "low"}},
                {"event": "token", "data": {"text": "你好"}},
                {"event": "done", "data": {}},
            ),
            context=CONTEXT,
        ))

    assert frames == [
        'event: meta\ndata: {"effort": "low"}\n\n',
        'event: token\ndata: {"text": "你好"}\n\n',
        'event: done\ndata: {}\n\n',
    ]
    messages = [record.getMessage() for record in caplog.records]
    assert any("sse stream start" in m and "conversation=7" in m and "effort=low" in m for m in messages)
    assert any(
        "sse stream complete" in m and "'meta': 1" in m and "'token': 1" in m and "'done': 1" in m
        for m in messages
    )
    # 逐帧 DEBUG：事件名 + 帧字节数，不含患者原文
    assert any("sse frame" in m and "event=token" in m and "bytes=" in m for m in messages)


def test_client_disconnect_is_logged_and_cancellation_propagates(
    caplog: pytest.LogCaptureFixture,
) -> None:
    async def cancelled_midway() -> AsyncIterator[dict[str, object]]:
        yield {"event": "meta", "data": {"effort": "low"}}
        # server-java 取消上游订阅时 uvicorn 以 disconnect 取消本流（票 33 的静默路径）
        raise asyncio.CancelledError

    with caplog.at_level(logging.WARNING, logger="app.api.sse"), pytest.raises(asyncio.CancelledError):
        _drain(log_sse_stream(cancelled_midway(), context=CONTEXT))

    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert any("cancelled by client" in r.getMessage() and "conversation=7" in r.getMessage() for r in warnings)


def test_stream_failure_is_logged_with_exception_and_propagates(
    caplog: pytest.LogCaptureFixture,
) -> None:
    async def failing() -> AsyncIterator[dict[str, object]]:
        yield {"event": "meta", "data": {"effort": "low"}}
        raise ValueError("graph exploded")

    with caplog.at_level(logging.ERROR, logger="app.api.sse"), pytest.raises(ValueError, match="graph exploded"):
        _drain(log_sse_stream(failing(), context=CONTEXT))

    errors = [r for r in caplog.records if r.levelno == logging.ERROR]
    assert any("sse stream failed" in r.getMessage() and "conversation=7" in r.getMessage() for r in errors)
    assert any(r.exc_info is not None for r in errors)

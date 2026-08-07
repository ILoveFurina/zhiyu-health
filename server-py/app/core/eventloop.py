"""Windows 事件循环修正：psycopg 异步拒绝 ProactorEventLoop，强制 SelectorEventLoop。

Python 3.14 起事件循环策略机制整体私有化并弃用（3.16 移除）：公共策略类别名
（WindowsSelectorEventLoopPolicy 等）与 set_event_loop_policy 均无公共替代，
mypy 亦不再识别旧类名。此处直接使用私有实现类 asyncio._WindowsSelectorEventLoopPolicy，
与旧别名同语义；set_event_loop_policy 自身的一条 DeprecationWarning 在 3.16 前
无法消除（CPython 的迁移方向是 Runner/loop_factory，不适用 import 期全局切换），
届时需随 CPython 策略系统演进重新评估。uvicorn 启动已由 scripts/run-server-py.py
的 loop factory patch 覆盖，不依赖本模块。
"""

import asyncio
import sys


def force_selector_event_loop_on_windows() -> None:
    """Windows 上把默认事件循环策略切到 Selector；其他平台为空操作。"""
    if sys.platform == "win32":
        # CPython 3.14 私有化的策略实现类（见模块 docstring），mypy typeshed 已收录
        asyncio.set_event_loop_policy(asyncio._WindowsSelectorEventLoopPolicy())  # noqa: SLF001

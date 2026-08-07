"""server-py 本地启动器（Windows 专用：psycopg 异步要求 SelectorEventLoop）。

背景（README/AGENTS.md 记录）：uvicorn 0.51 默认 loop="auto"，在 Windows 上
产出 ProactorEventLoop，而 psycopg 异步模式拒绝在其上运行，启动即崩。
文档曾 patch uvicorn.loops.asyncio.asyncio_loop_factory，但对 0.51 的 auto
分支不生效；此处直接 patch uvicorn.loops.auto.auto_loop_factory 强制
SelectorEventLoop。

用法（仓库根目录）：
    uv run python scripts/run-server-py.py
    # 端口可用环境变量覆盖：SERVER_PY_PORT=8001 uv run python scripts/run-server-py.py
"""
from __future__ import annotations

import asyncio
import os

import uvicorn
import uvicorn.loops.auto as uv_auto

uv_auto.auto_loop_factory = lambda use_subprocess=False: asyncio.SelectorEventLoop

port = int(os.environ.get("SERVER_PY_PORT", "8000"))
uvicorn.run("app.main:app", app_dir="server-py", host="0.0.0.0", port=port)

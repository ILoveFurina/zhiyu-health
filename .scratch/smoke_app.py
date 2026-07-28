"""冒烟专用：注入假流的 server-py 实例（票 28 联调用，.env 无方舟密钥时替代真实 LLM）。

运行：uv run uvicorn --app-dir .scratch smoke_app:app --port 8000
"""

import sys
from collections.abc import AsyncIterator
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server-py"))

from app.main import create_app  # noqa: E402


class FakeStreamRunner:
    async def astream_reply(self, messages: list[dict[str, str]], effort: str) -> AsyncIterator[str]:
        for token in ["[假流]你好", "，我是小愈", "。"]:
            yield token


app = create_app(agent_runner=FakeStreamRunner())

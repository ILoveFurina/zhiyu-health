"""server-java 回调入口的共享依赖。"""

import secrets
from typing import Annotated

from fastapi import Depends, Header, HTTPException, Request


async def verify_agent_callback_token(
    request: Request,
    x_agent_callback_token: Annotated[str | None, Header()] = None,
) -> None:
    """校验 server-java 回调令牌；文案与 server-java AgentCallbackAuthFilter 一致。"""
    expected = request.app.state.agent_callback_secret
    if not expected or x_agent_callback_token is None or not secrets.compare_digest(
        expected, x_agent_callback_token
    ):
        raise HTTPException(status_code=401, detail="Agent 回调认证失败")


AgentCallbackAuth = Annotated[None, Depends(verify_agent_callback_token)]

"""server-java 业务回调通道与统一失败语义。

server-py 没有业务写入权：工具、导诊目录和预问诊摘要都经这个已鉴权 HTTP 通道回调
server-java。网络失败和业务拒绝是可解释的运行结果，调用型模块可用 ``forward_*`` 将其
降级成文本，避免异常穿透 LangGraph 后掐断 SSE。
"""

from typing import Any

import httpx


class BusinessCallbackClient:
    def __init__(
        self,
        base_url: str,
        timeout: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
        callback_secret: str = "",
    ) -> None:
        # server-java 是内网直连目标；绕过系统代理可避免本机代理把回调变成 502。
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout,
            transport=transport,
            trust_env=False,
            headers={"X-Agent-Callback-Token": callback_secret} if callback_secret else None,
        )

    async def _request_json(self, method: str, path: str, **kwargs: Any) -> Any:
        response = await self._client.request(method, path, **kwargs)
        response.raise_for_status()
        return response.json()

    async def get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        return await self._request_json("GET", path, params=params)

    async def post(self, path: str, payload: dict[str, Any]) -> Any:
        return await self._request_json("POST", path, json=payload)

    async def aclose(self) -> None:
        await self._client.aclose()


def callback_error_text(action: str, error: httpx.HTTPError) -> str:
    """保留 server-java 的可读拒绝原因，但不泄露非 JSON 响应内容。"""
    if isinstance(error, httpx.HTTPStatusError):
        detail: str | None = None
        try:
            body = error.response.json().get("detail")
        except ValueError:
            body = None
        if isinstance(body, dict):
            detail = body.get("message") if isinstance(body.get("message"), str) else None
        elif isinstance(body, str):
            detail = body
        return f"{action}失败：{detail or f'HTTP {error.response.status_code}'}"
    return f"{action}失败：业务后端暂不可用，请稍后重试"


async def forward_get(
    client: BusinessCallbackClient,
    path: str,
    params: dict[str, Any] | None = None,
    *,
    action: str,
) -> dict[str, Any] | str:
    try:
        return dict(await client.get(path, params=params))
    except httpx.HTTPError as error:
        return callback_error_text(action, error)


async def forward_post(
    client: BusinessCallbackClient,
    path: str,
    payload: dict[str, Any],
    *,
    action: str,
) -> dict[str, Any] | str:
    try:
        return dict(await client.post(path, payload))
    except httpx.HTTPError as error:
        return callback_error_text(action, error)

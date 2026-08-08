"""业务回调客户端配置回归（票 60 后续发现）。

httpx 默认 trust_env=True 会拾取环境变量与 Windows 系统代理（注册表），把
server-java 业务回调塞进本机代理 → 全部 502（预问诊科室恒 null、导诊工具全失败）。
MockTransport 不触网，既有测试测不出该回归；此处直接断言生产客户端底层
AsyncClient 显式关闭 trust_env，保证回调直连 server-java。
"""

import asyncio

from app.tools.business import BusinessCallbackClient


def test_business_callback_client_disables_trust_env() -> None:
    client = BusinessCallbackClient("http://server-java.test")
    try:
        assert client._client.trust_env is False
    finally:
        asyncio.run(client.aclose())

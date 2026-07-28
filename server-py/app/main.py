"""server-py 装配：Agent 编排的 HTTP 壳（ADR-0009）。

只挂两类接口：健康检查与 Agent 对话（供 server-java 经 SSE 调用）。
业务 API、鉴权、会话持久化均不在本端。
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.agent.runner import AgentRunner, LazySettingsAgentRunner
from app.api.agent import router as agent_router
from app.api.health import router as health_router
from app.config import get_settings, get_web_settings
from app.db.clients import create_knowledge_clients
from app.services.chat import AgentChatService
from app.services.health import HealthChecker, HealthService
from app.tools.business import BusinessCallbackClient


def create_app(
    health_service: HealthChecker | None = None,
    agent_runner: AgentRunner | None = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.chat_service = AgentChatService(agent_runner or LazySettingsAgentRunner())
        if health_service is not None:
            # 注入装配路径（测试）：不触碰真实存储与 settings
            app.state.health_service = health_service
            yield
            return
        settings = get_settings()
        clients = create_knowledge_clients(settings)
        app.state.health_service = HealthService(clients)
        # 业务工具回调通道（薄壳）：装配在此，具体业务工具由后续票注入 Agent 图
        app.state.business_client = BusinessCallbackClient(settings.server_java_base_url)
        try:
            yield
        finally:
            await app.state.business_client.aclose()
            await clients.close()

    application = FastAPI(title="智愈 Agent 层", lifespan=lifespan)
    application.add_middleware(
        CORSMiddleware,
        allow_origins=get_web_settings().cors_origin_list,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    application.include_router(health_router, prefix="/api")
    application.include_router(agent_router, prefix="/api")
    return application


app = create_app()

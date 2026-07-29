"""server-py 装配：Agent 编排的 HTTP 壳（ADR-0009）。

只挂两类接口：健康检查与 Agent 对话（供 server-java 经 SSE 调用）。
业务 API、鉴权、会话持久化均不在本端。
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.agent.runner import AgentRunner, LazySettingsAgentRunner
from app.agent.vision.interpreter import LazyVisionInterpreter, VisionInterpreter
from app.api.agent import router as agent_router
from app.api.health import router as health_router
from app.api.vision import router as vision_router
from app.config import get_settings, get_web_settings
from app.db.clients import create_knowledge_clients
from app.services.chat import AgentChatService
from app.services.health import HealthChecker, HealthService
from app.tools.business import BusinessCallbackClient, build_business_tools


def create_app(
    health_service: HealthChecker | None = None,
    agent_runner: AgentRunner | None = None,
    agent_auth_secret: str | None = None,
    vision_interpreter: VisionInterpreter | None = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if health_service is not None:
            # 注入装配路径（测试）：不触碰真实存储与 settings
            app.state.chat_service = AgentChatService(agent_runner or LazySettingsAgentRunner())
            app.state.health_service = health_service
            app.state.agent_callback_secret = agent_auth_secret
            app.state.vision_interpreter = vision_interpreter or LazyVisionInterpreter()
            yield
            return
        settings = get_settings()
        app.state.agent_callback_secret = settings.agent_callback_secret
        clients = create_knowledge_clients(settings)
        app.state.health_service = HealthService(clients)
        app.state.business_client = BusinessCallbackClient(
            settings.server_java_base_url, callback_secret=settings.agent_callback_secret
        )
        runner = agent_runner or LazySettingsAgentRunner(
            build_business_tools(app.state.business_client)
        )
        app.state.chat_service = AgentChatService(runner)
        app.state.vision_interpreter = vision_interpreter or LazyVisionInterpreter()
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
    application.include_router(vision_router, prefix="/api")
    return application


app = create_app()

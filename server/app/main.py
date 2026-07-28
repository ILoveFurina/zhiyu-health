from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.engine import Engine

from app.agent.runner import AgentRunner, build_langgraph_agent_runner
from app.api.c.auth import router as c_auth_router
from app.api.c.chat import router as c_chat_router
from app.api.health import router as health_router
from app.config import get_settings, get_web_settings
from app.db.base import Base
from app.db.clients import StorageClients, create_storage_clients
from app.models import Conversation, Message, Patient  # noqa: F401  # 注册建表元数据
from app.services.auth import PatientService, TokenService
from app.services.chat import ChatService
from app.services.conversation import ConversationService
from app.services.health import HealthChecker, HealthService
from app.services.red_flag import RedFlagService


def create_app(
    health_service: HealthChecker | None = None,
    *,
    engine: Engine | None = None,
    agent_runner: AgentRunner | None = None,
    token_secret: str | None = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        clients: StorageClients | None = None
        if engine is None:
            # 生产装配：存储与 LLM 全部来自 settings
            settings = get_settings()
            clients = create_storage_clients(settings)
            pg_engine = clients.postgres
            runner = agent_runner or build_langgraph_agent_runner(settings)
            secret, expire_minutes = settings.jwt_secret, settings.jwt_expire_minutes
            app.state.health_service = health_service or HealthService(clients)
        else:
            # 测试装配：外部注入测试库、fake Agent 与固定令牌密钥
            if agent_runner is None:
                raise ValueError("注入 engine 时必须同时注入 agent_runner")
            pg_engine = engine
            runner = agent_runner
            secret, expire_minutes = token_secret or "test-secret", 720
            if health_service is not None:
                app.state.health_service = health_service

        Base.metadata.create_all(pg_engine)
        app.state.token_service = TokenService(secret, expire_minutes)
        app.state.patient_service = PatientService(pg_engine)
        app.state.conversation_service = ConversationService(pg_engine)
        app.state.chat_service = ChatService(
            app.state.conversation_service, RedFlagService(), runner
        )
        try:
            yield
        finally:
            if clients is not None:
                await clients.close()

    application = FastAPI(title="智愈 API", lifespan=lifespan)
    application.add_middleware(
        CORSMiddleware,
        allow_origins=get_web_settings().cors_origin_list,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    application.include_router(health_router, prefix="/api")
    application.include_router(c_auth_router, prefix="/api")
    application.include_router(c_chat_router, prefix="/api")
    return application


app = create_app()

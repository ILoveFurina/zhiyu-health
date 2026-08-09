"""生产与测试共用的 FastAPI HTTP 壳。

本模块只创建应用、配置 CORS 并挂载路由，不导入生产 bootstrap、读取 settings 或构建外部依赖。
调用方必须显式传入 lifespan 与允许的来源，从而让测试装配保持无生产副作用。
"""

from collections.abc import Callable
from typing import AsyncContextManager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.agent import router as agent_router
from app.api.clinical import router as clinical_router
from app.api.health import router as health_router
from app.api.knowledge import router as knowledge_router
from app.api.medication import router as medication_router
from app.api.vision import router as vision_router
from app.api.voice import router as voice_router

Lifespan = Callable[[FastAPI], AsyncContextManager[None]]


def build_http_app(lifespan: Lifespan, cors_origins: list[str]) -> FastAPI:
    application = FastAPI(title="智愈 Agent 层", lifespan=lifespan)
    application.add_middleware(
        CORSMiddleware,
        allow_origins=cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    for router in (
        health_router,
        agent_router,
        vision_router,
        clinical_router,
        knowledge_router,
        medication_router,
        voice_router,
    ):
        application.include_router(router, prefix="/api")
    return application

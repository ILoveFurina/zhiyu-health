"""server-py HTTP 入口：事件循环、FastAPI 创建、路由挂载与生产 lifespan。

本进程只对 server-java 暴露 Agent、视觉、语音、临床文本、知识图谱和健康检查接口；
鉴权、审计、红线规则与业务持久化仍由 server-java 负责。生产依赖装配见
``app.bootstrap``，离线测试使用 ``app.testing.create_test_app``。
"""

from typing import Any

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.agent import router as agent_router
from app.api.clinical import router as clinical_router
from app.api.health import router as health_router
from app.api.knowledge import router as knowledge_router
from app.api.medication import router as medication_router
from app.api.vision import router as vision_router
from app.api.voice import router as voice_router
from app.bootstrap import production_lifespan
from app.config import get_web_settings
from app.core.eventloop import force_selector_event_loop_on_windows
from app.core.logging import configure_logging

force_selector_event_loop_on_windows()


def build_http_app(lifespan: Any) -> FastAPI:
    """创建共同 HTTP 壳；生产和测试只替换 lifespan 提供的运行期依赖。"""
    application = FastAPI(title="智愈 Agent 层", lifespan=lifespan)
    application.add_middleware(
        CORSMiddleware,
        allow_origins=get_web_settings().cors_origin_list,
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


def create_app() -> FastAPI:
    configure_logging()
    return build_http_app(production_lifespan)


app = create_app()

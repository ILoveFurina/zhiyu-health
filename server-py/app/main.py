"""server-py HTTP 入口：事件循环、FastAPI 创建、路由挂载与生产 lifespan。

本进程只对 server-java 暴露 Agent、视觉、语音、临床文本、知识图谱和健康检查接口；
鉴权、审计、红线规则与业务持久化仍由 server-java 负责。生产依赖装配见
``app.bootstrap``，离线测试使用 ``app.testing.create_test_app``。
"""

from fastapi import FastAPI
from app.bootstrap import production_lifespan
from app.config import get_web_settings
from app.core.eventloop import force_selector_event_loop_on_windows
from app.core.logging import configure_logging
from app.http import build_http_app

force_selector_event_loop_on_windows()


def create_app() -> FastAPI:
    configure_logging()
    return build_http_app(production_lifespan, get_web_settings().cors_origin_list)


app = create_app()

"""FastAPI 运行期依赖的集中容器。

路由仍按 FastAPI 约定从 ``app.state`` 读取，但字段只在这里一次性安装，避免生产与测试
各维护一套隐式字段清单。容器不负责构建依赖，也不隐藏资源关闭顺序。
"""

from dataclasses import dataclass
from typing import Any

from fastapi import FastAPI


@dataclass(frozen=True)
class ApplicationRuntime:
    chat_service: Any
    health_service: Any
    agent_callback_secret: str | None
    vision_interpreter: Any
    clinical_generator: Any
    graph_projector: Any
    voice_service: Any
    medication_streamer: Any
    knowledge_embedder: Any


def install_runtime(app: FastAPI, runtime: ApplicationRuntime) -> None:
    for name, value in runtime.__dict__.items():
        setattr(app.state, name, value)

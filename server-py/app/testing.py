"""离线测试专用的显式应用装配。

调用本入口永远使用注入依赖，不读取 settings、不连接存储，也不构建真实回调客户端。
参数保持贴近各测试场景，生产入口 ``main.create_app`` 因此无需暴露整套可选参数。
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.agent.clinical import ClinicalGenerator, LazyClinicalGenerator
from app.agent.emotion import EmotionJudge
from app.agent.medication import LazyMedicationKnowledgeStreamer, MedicationKnowledgeStreamer
from app.agent.preconsult import PreconsultJudge
from app.agent.runner import AgentRunner, LazySettingsAgentRunner
from app.agent.vision.interpreter import LazyVisionInterpreter, VisionInterpreter
from app.http import build_http_app
from app.runtime import ApplicationRuntime, install_runtime
from app.services.chat import AgentChatService
from app.services.directory import DepartmentDirectory
from app.services.health import HealthChecker
from app.services.voice import LazyVoiceService, VoiceService
from app.tools.preconsult_callback import SummaryCallback


def create_test_app(
    health_service: HealthChecker,
    agent_runner: AgentRunner | None = None,
    agent_auth_secret: str | None = None,
    vision_interpreter: VisionInterpreter | None = None,
    clinical_generator: ClinicalGenerator | None = None,
    rag_available: bool = False,
    graph_available: bool = False,
    graph_projector: object | None = None,
    emotion_judge: EmotionJudge | None = None,
    preconsult_judge: PreconsultJudge | None = None,
    directory: DepartmentDirectory | None = None,
    summary_callback: SummaryCallback | None = None,
    voice_service: VoiceService | None = None,
    medication_streamer: MedicationKnowledgeStreamer | None = None,
    knowledge_embedder: object | None = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        install_runtime(
            app,
            ApplicationRuntime(
                chat_service=AgentChatService(
                    agent_runner or LazySettingsAgentRunner(),
                    rag_available=rag_available,
                    graph_available=graph_available,
                    emotion_judge=emotion_judge,
                    preconsult_judge=preconsult_judge,
                    directory=directory,
                    summary_callback=summary_callback,
                ),
                health_service=health_service,
                agent_callback_secret=agent_auth_secret,
                vision_interpreter=vision_interpreter or LazyVisionInterpreter(),
                clinical_generator=clinical_generator or LazyClinicalGenerator(),
                graph_projector=graph_projector,
                voice_service=voice_service or LazyVoiceService(),
                medication_streamer=medication_streamer or LazyMedicationKnowledgeStreamer(),
                knowledge_embedder=knowledge_embedder,
            ),
        )
        yield

    return build_http_app(lifespan, ["http://localhost:5173"])

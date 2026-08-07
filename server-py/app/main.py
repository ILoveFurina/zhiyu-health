"""server-py 装配：Agent 编排的 HTTP 壳（ADR-0009）。

只挂两类接口：健康检查与 Agent 对话（供 server-java 经 SSE 调用）。
业务 API、鉴权、会话持久化均不在本端。
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.agent.runner import AgentRunner, LazySettingsAgentRunner
from app.agent.clinical import ClinicalGenerator, LazyClinicalGenerator
from app.agent.emotion import EmotionJudge
from app.agent.medication import LazyMedicationKnowledgeStreamer, MedicationKnowledgeStreamer
from app.agent.vision.interpreter import LazyVisionInterpreter, VisionInterpreter
from app.api.agent import router as agent_router
from app.api.clinical import router as clinical_router
from app.api.health import router as health_router
from app.api.knowledge import router as knowledge_router
from app.api.medication import router as medication_router
from app.api.vision import router as vision_router
from app.api.voice import router as voice_router
from app.config import get_settings, get_web_settings
from app.core.eventloop import force_selector_event_loop_on_windows
from app.core.logging import configure_logging
from app.db.clients import create_knowledge_clients
from app.services.chat import AgentChatService
from app.services.graph import build_graph_projector, build_graph_traverser
from app.services.health import HealthChecker, HealthService
from app.services.knowledge import build_knowledge_retriever
from app.services.voice import LazyVoiceService, VoiceService
from app.tools.business import BusinessCallbackClient, build_business_tools

# Windows 默认 ProactorEventLoop 与 psycopg 异步不兼容，切到 SelectorEventLoop。
# 生产部署在 Linux 不受影响（Linux 默认即兼容的 epoll 循环）。
force_selector_event_loop_on_windows()


def create_app(
    health_service: HealthChecker | None = None,
    agent_runner: AgentRunner | None = None,
    agent_auth_secret: str | None = None,
    vision_interpreter: VisionInterpreter | None = None,
    clinical_generator: ClinicalGenerator | None = None,
    rag_available: bool = False,
    graph_available: bool = False,
    graph_projector: object | None = None,
    emotion_judge: EmotionJudge | None = None,
    voice_service: VoiceService | None = None,
    medication_streamer: MedicationKnowledgeStreamer | None = None,
) -> FastAPI:
    # uvicorn 只配置自身 logger；app.* 的流生命周期日志需显式接管（票 33）
    configure_logging()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if health_service is not None:
            # 注入装配路径（测试）：不触碰真实存储与 settings
            app.state.chat_service = AgentChatService(
                agent_runner or LazySettingsAgentRunner(),
                rag_available=rag_available,
                graph_available=graph_available,
                emotion_judge=emotion_judge,
            )
            app.state.health_service = health_service
            app.state.agent_callback_secret = agent_auth_secret
            app.state.vision_interpreter = vision_interpreter or LazyVisionInterpreter()
            app.state.clinical_generator = clinical_generator or LazyClinicalGenerator()
            app.state.graph_projector = graph_projector
            app.state.voice_service = voice_service or LazyVoiceService()
            app.state.medication_streamer = medication_streamer or LazyMedicationKnowledgeStreamer()
            yield
            return
        settings = get_settings()
        app.state.agent_callback_secret = settings.agent_callback_secret
        clients = create_knowledge_clients(settings)
        app.state.health_service = HealthService(clients)
        app.state.business_client = BusinessCallbackClient(
            settings.server_java_base_url, callback_secret=settings.agent_callback_secret
        )
        # 知识检索器（ADR-0010）：database_url/embedding 未配置时为 None，运行时检索降级
        knowledge_retriever = build_knowledge_retriever(settings)
        # 图谱遍历器（ADR-0013）：Neo4j 驱动由 KnowledgeClients 持有，未配置时为 None
        graph_traverser = build_graph_traverser(clients)
        # 图谱投影 service（ADR-0013 决策 2）：B 端可视化经 server-java 转调本接口
        built_projector = build_graph_projector(clients)
        runner = agent_runner or LazySettingsAgentRunner(
            build_business_tools(app.state.business_client), knowledge_retriever, graph_traverser
        )
        app.state.chat_service = AgentChatService(
            runner,
            rag_available=knowledge_retriever is not None,
            graph_available=graph_traverser is not None,
        )
        app.state.graph_projector = built_projector
        app.state.vision_interpreter = vision_interpreter or LazyVisionInterpreter()
        app.state.clinical_generator = clinical_generator or LazyClinicalGenerator()
        app.state.voice_service = voice_service or LazyVoiceService()
        app.state.medication_streamer = medication_streamer or LazyMedicationKnowledgeStreamer()
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
    application.include_router(clinical_router, prefix="/api")
    application.include_router(knowledge_router, prefix="/api")
    application.include_router(medication_router, prefix="/api")
    application.include_router(voice_router, prefix="/api")
    return application


app = create_app()

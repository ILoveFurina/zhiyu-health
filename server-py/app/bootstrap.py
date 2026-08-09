"""生产依赖构建与资源生命周期。

这里是连接真实 settings、存储客户端、server-java 回调和 LLM 懒适配器的唯一位置。
测试不会进入本模块，因此不会因某个 fake 是否传入而意外触碰真实外部资源。
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.agent.clinical import LazyClinicalGenerator
from app.agent.medication import LazyMedicationKnowledgeStreamer
from app.agent.runner import LazySettingsAgentRunner
from app.agent.vision.interpreter import LazyVisionInterpreter
from app.config import get_settings
from app.db.clients import create_knowledge_clients
from app.runtime import ApplicationRuntime, install_runtime
from app.services.chat import AgentChatService
from app.services.directory import CallbackDepartmentDirectory
from app.services.graph import build_graph_projector, build_graph_traverser
from app.services.health import HealthService
from app.services.knowledge import build_knowledge_retriever
from app.services.voice import LazyVoiceService
from app.tools.business import build_business_tools
from app.tools.callback import BusinessCallbackClient
from app.tools.department import build_department_tools
from app.tools.preconsult_callback import PreconsultationSummaryCallback


@asynccontextmanager
async def production_lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    clients = create_knowledge_clients(settings)
    business_client = BusinessCallbackClient(
        settings.server_java_base_url, callback_secret=settings.agent_callback_secret
    )
    knowledge_retriever = build_knowledge_retriever(settings)
    graph_traverser = build_graph_traverser(clients)
    directory = CallbackDepartmentDirectory(business_client)
    runner = LazySettingsAgentRunner(
        [*build_business_tools(business_client), *build_department_tools(directory)],
        knowledge_retriever,
        graph_traverser,
    )
    install_runtime(
        app,
        ApplicationRuntime(
            chat_service=AgentChatService(
                runner,
                rag_available=knowledge_retriever is not None,
                graph_available=graph_traverser is not None,
                directory=directory,
                summary_callback=PreconsultationSummaryCallback(business_client),
            ),
            health_service=HealthService(clients),
            agent_callback_secret=settings.agent_callback_secret,
            vision_interpreter=LazyVisionInterpreter(),
            clinical_generator=LazyClinicalGenerator(),
            graph_projector=build_graph_projector(clients),
            voice_service=LazyVoiceService(),
            medication_streamer=LazyMedicationKnowledgeStreamer(),
        ),
    )
    try:
        yield
    finally:
        await business_client.aclose()
        await clients.close()

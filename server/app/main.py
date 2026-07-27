from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.health import router as health_router
from app.config import get_settings, get_web_settings
from app.db.clients import create_storage_clients
from app.services.health import HealthChecker, HealthService


def create_app(health_service: HealthChecker | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if health_service is not None:
            app.state.health_service = health_service
            yield
            return

        settings = get_settings()
        clients = create_storage_clients(settings)
        app.state.health_service = HealthService(clients)
        try:
            yield
        finally:
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
    return application


app = create_app()

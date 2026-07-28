import secrets
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import Engine

from app.api.b.auth import router as auth_router
from app.api.b.organization import router as organization_router
from app.api.health import router as health_router
from app.config import get_settings, get_web_settings
from app.db.clients import create_storage_clients
from app.db.seed import initialize_database
from app.services.health import HealthChecker, HealthService


def create_app(
    health_service: HealthChecker | None = None,
    database_engine: Engine | None = None,
    *,
    seed_database: bool = False,
    jwt_secret: str | None = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.jwt_secret = jwt_secret or secrets.token_urlsafe(32)
        if database_engine is not None:
            app.state.database_engine = database_engine
            initialize_database(database_engine, with_seed=seed_database)
            if health_service is not None:
                app.state.health_service = health_service
            yield
            database_engine.dispose()
            return
        if health_service is not None:
            app.state.health_service = health_service
            yield
            return

        settings = get_settings()
        clients = create_storage_clients(settings)
        app.state.health_service = HealthService(clients)
        app.state.database_engine = clients.postgres
        app.state.jwt_secret = (
            settings.jwt_secret.get_secret_value()
            if settings.jwt_secret is not None
            else app.state.jwt_secret
        )
        initialize_database(clients.postgres, with_seed=seed_database)
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
    application.include_router(auth_router, prefix="/api")
    application.include_router(organization_router, prefix="/api")
    return application


app = create_app(seed_database=True)

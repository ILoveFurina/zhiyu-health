import asyncio
from collections.abc import Awaitable, Callable
from typing import Protocol, cast

from neo4j.exceptions import Neo4jError
from redis.exceptions import RedisError
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.db.clients import StorageClients


class HealthChecker(Protocol):
    async def check(self) -> dict[str, object]: ...


class HealthService:
    def __init__(self, clients: StorageClients) -> None:
        self._clients = clients

    async def check(self) -> dict[str, object]:
        results = await asyncio.gather(
            self._status(self._check_postgres),
            self._status(self._check_redis),
            self._status(self._check_neo4j),
        )
        services = dict(zip(("postgres", "redis", "neo4j"), results, strict=True))
        overall = "ok" if all(item["status"] == "ok" for item in results) else "degraded"
        return {"status": overall, "services": services}

    @staticmethod
    async def _status(check: Callable[[], Awaitable[None]]) -> dict[str, str]:
        try:
            await check()
        except (Neo4jError, OSError, RedisError, SQLAlchemyError):
            return {"status": "error"}
        return {"status": "ok"}

    async def _check_postgres(self) -> None:
        async with self._clients.postgres.connect() as connection:
            result = await connection.execute(
                text("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
            )
            result.scalar_one()

    async def _check_redis(self) -> None:
        await cast(Awaitable[bool], self._clients.redis.ping())

    async def _check_neo4j(self) -> None:
        await self._clients.neo4j.verify_connectivity()

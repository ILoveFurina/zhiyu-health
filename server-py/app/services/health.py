"""知识存储探活：Agent 层只直连 Neo4j + pgvector（只读检索）。"""

from collections.abc import Awaitable, Callable
from typing import Protocol

from neo4j.exceptions import Neo4jError
from psycopg import AsyncConnection

from app.db.clients import KnowledgeClients


class HealthChecker(Protocol):
    async def check(self) -> dict[str, object]: ...


class HealthService:
    def __init__(self, clients: KnowledgeClients) -> None:
        self._clients = clients

    async def check(self) -> dict[str, object]:
        neo4j = await self._status(self._check_neo4j)
        pg = await self._status(self._check_pg)
        overall = "ok" if neo4j["status"] == "ok" and pg["status"] == "ok" else "degraded"
        return {"status": overall, "services": {"neo4j": neo4j, "pgvector": pg}}

    @staticmethod
    async def _status(check: Callable[[], Awaitable[None]]) -> dict[str, str]:
        try:
            await check()
        except (Neo4jError, OSError):
            return {"status": "error"}
        return {"status": "ok"}

    async def _check_neo4j(self) -> None:
        await self._clients.neo4j.verify_connectivity()

    async def _check_pg(self) -> None:
        # database_url 未配置时检索降级走裸 LLM，探活标 unavailable（不阻断整体）
        dsn = self._clients.pg_dsn
        if dsn is None:
            raise OSError("pgvector 未配置")
        conn = await AsyncConnection.connect(dsn, timeout=3)
        try:
            await conn.execute("SELECT 1")
        finally:
            await conn.close()

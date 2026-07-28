"""知识存储探活：Agent 层只直连 Neo4j（pgvector 只读检索接入后在此扩展）。"""

from collections.abc import Awaitable, Callable
from typing import Protocol

from neo4j.exceptions import Neo4jError

from app.db.clients import KnowledgeClients


class HealthChecker(Protocol):
    async def check(self) -> dict[str, object]: ...


class HealthService:
    def __init__(self, clients: KnowledgeClients) -> None:
        self._clients = clients

    async def check(self) -> dict[str, object]:
        neo4j = await self._status(self._check_neo4j)
        overall = "ok" if neo4j["status"] == "ok" else "degraded"
        return {"status": overall, "services": {"neo4j": neo4j}}

    @staticmethod
    async def _status(check: Callable[[], Awaitable[None]]) -> dict[str, str]:
        try:
            await check()
        except (Neo4jError, OSError):
            return {"status": "error"}
        return {"status": "ok"}

    async def _check_neo4j(self) -> None:
        await self._clients.neo4j.verify_connectivity()

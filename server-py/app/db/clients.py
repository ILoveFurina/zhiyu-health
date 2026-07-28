"""知识存储客户端：Neo4j 直连（仅 server-py 持有，ADR-0009）。

pgvector 只读检索客户端由后续知识检索票在此扩展；业务库连接不进入 Agent 层。
"""

from dataclasses import dataclass

from neo4j import AsyncDriver, AsyncGraphDatabase

from app.config import Settings


@dataclass
class KnowledgeClients:
    neo4j: AsyncDriver

    async def close(self) -> None:
        await self.neo4j.close()


def create_knowledge_clients(settings: Settings) -> KnowledgeClients:
    return KnowledgeClients(
        neo4j=AsyncGraphDatabase.driver(
            settings.neo4j_uri,
            auth=(settings.neo4j_user, settings.neo4j_password),
            connection_timeout=3,
        ),
    )

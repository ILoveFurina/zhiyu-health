from dataclasses import dataclass

from neo4j import AsyncDriver, AsyncGraphDatabase
from redis.asyncio import Redis
from sqlalchemy import Engine, create_engine

from app.config import Settings


@dataclass
class StorageClients:
    postgres: Engine
    redis: Redis
    neo4j: AsyncDriver

    async def close(self) -> None:
        self.postgres.dispose()
        await self.redis.aclose()
        await self.neo4j.close()


def create_storage_clients(settings: Settings) -> StorageClients:
    return StorageClients(
        postgres=create_engine(
            settings.database_url,
            pool_pre_ping=True,
            connect_args={"connect_timeout": 3},
        ),
        redis=Redis.from_url(
            settings.redis_url,
            socket_connect_timeout=3,
            socket_timeout=3,
        ),
        neo4j=AsyncGraphDatabase.driver(
            settings.neo4j_uri,
            auth=(settings.neo4j_user, settings.neo4j_password),
            connection_timeout=3,
        ),
    )

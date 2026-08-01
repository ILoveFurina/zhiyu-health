"""知识存储客户端：Neo4j 直连 + pgvector 只读检索（仅 server-py 持有，ADR-0009）。

业务库写入、Redis 号源计数均归 server-java；server-py 对 knowledge_chunks 只读
检索（embed query + Top-K + 阈值过滤），不读取或写入业务实体。
"""

from dataclasses import dataclass

from neo4j import AsyncDriver, AsyncGraphDatabase
from psycopg import AsyncConnection

from app.config import Settings
from app.core.contracts import get_contracts


@dataclass
class KnowledgeClients:
    neo4j: AsyncDriver
    # pgvector 只读检索连接；database_url 未配置时为 None，运行时检索静默降级走裸 LLM。
    # 检索按请求开短连接（只读、低频），避免引入连接池依赖。
    pg_dsn: str | None

    async def close(self) -> None:
        await self.neo4j.close()


def create_knowledge_clients(settings: Settings) -> KnowledgeClients:
    neo4j = AsyncGraphDatabase.driver(
        settings.neo4j_uri,
        auth=(settings.neo4j_user, settings.neo4j_password),
        connection_timeout=3,
    )
    # 维度路径 B（ADR-0010）：settings 维度 == 契约维度，不一致 fail-fast。
    # DDL 写死 vector(1024)，与契约维度同源，此处校验配置侧防漂移。
    contract_dim = get_contracts().knowledge.embedding_dimension
    if settings.knowledge_embedding_dimension != contract_dim:
        raise RuntimeError(
            "向量维度配置与契约不一致（KNOWLEDGE_EMBEDDING_DIMENSION "
            f"{settings.knowledge_embedding_dimension} != 契约 {contract_dim}），"
            "请同步 application.yml 与 contracts/knowledge.json"
        )
    # database_url 为空（如测试或未配置检索）时不建连接，运行时检索降级走裸 LLM
    return KnowledgeClients(neo4j=neo4j, pg_dsn=_normalize_dsn(settings.database_url))


def _normalize_dsn(database_url: str) -> str | None:
    """规整 DSN：.env 用 SQLAlchemy 风格 postgresql+psycopg://，psycopg 原生需纯 postgresql://。"""
    if not database_url:
        return None
    return database_url.replace("postgresql+psycopg://", "postgresql://", 1)


def libpq_dsn(dsn: str) -> str:
    """归一化 libpq 连接串：psycopg 只认 postgresql:// 或 postgres:// 方案的 URI。

    .env 的 DATABASE_URL 与 server-java/SQLAlchemy 生态共用，可能是
    postgresql+psycopg:// 方案；原样传入会被 psycopg 当作 key=value 串解析而报错。
    """
    if dsn.startswith("postgresql+psycopg://"):
        return "postgresql://" + dsn.removeprefix("postgresql+psycopg://")
    return dsn


async def acquire_pg_connection(dsn: str) -> AsyncConnection:
    """打开一个只读检索连接并注册 pgvector 适配器。"""
    from pgvector.psycopg import register_vector_async  # 延迟导入，避免无 pg 时的副作用

    # psycopg 3.3 的 connect() 无 timeout 形参（多余 kwargs 会并入 conninfo 而报错），
    # 连接超时只能走 libpq 连接选项 connect_timeout。
    conn = await AsyncConnection.connect(libpq_dsn(dsn), connect_timeout=5)
    await register_vector_async(conn)
    return conn

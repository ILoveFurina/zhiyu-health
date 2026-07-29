"""知识检索 service（ADR-0010）：pgvector 只读检索 + 召回块格式化。

server-py 运行时对 knowledge_chunks 只读（embed query + Top-K + 阈值过滤），
不读取或写入业务实体。检索失败或空召回静默降级返回空列表，由调用方走裸 LLM。
召回块以「【标题·科室】正文」结构化纯文本进入 LLM 上下文，衔接科室推荐。

KnowledgeChunk/KnowledgeRetriever 类型定义在 tools/knowledge.py（分层护栏：
tools 不得 import services，故类型归 tools，本模块反向 import 复用）。
"""

from langchain_core.embeddings import Embeddings

from app.config import Settings
from app.core.contracts import get_contracts
from app.db.clients import acquire_pg_connection
from app.tools.knowledge import KnowledgeChunk, KnowledgeRetriever

__all__ = ["KnowledgeChunk", "KnowledgeRetriever", "PgvectorKnowledgeRetriever", "build_knowledge_retriever"]


def _format(title: str, department: str, content: str) -> str:
    return f"【{title}·{department}】{content}"


class PgvectorKnowledgeRetriever:
    """生产实现：embed query -> pgvector cosine Top-K + 阈值过滤。"""

    def __init__(self, dsn: str, embedder: Embeddings, top_k: int, threshold: float) -> None:
        self._dsn = dsn
        self._embedder = embedder
        self._top_k = top_k
        self._threshold = threshold

    async def search(self, query: str) -> list[KnowledgeChunk]:
        # 任何环节失败（embedding 调用、DB 连接、SQL 执行）均静默降级返回空列表，
        # 由调用方走裸 LLM；不向用户暴露检索错误。
        try:
            query_vec = await self._embedder.aembed_query(query)
            conn = await acquire_pg_connection(self._dsn)
            try:
                cur = await conn.execute(
                    # cosine 距离 <=> 越小越相似；阈值过滤 + Top-K
                    # vector 列可能为 NULL（向量未回填），过滤掉只取已向量化行
                    """
                    SELECT title, department, content,
                           1 - (vector <=> %s::vector) AS score
                    FROM knowledge_chunks
                    WHERE vector IS NOT NULL
                      AND 1 - (vector <=> %s::vector) >= %s
                    ORDER BY vector <=> %s::vector
                    LIMIT %s
                    """,
                    (query_vec, query_vec, self._threshold, query_vec, self._top_k),
                )
                rows = await cur.fetchall()
            finally:
                await conn.close()
        except Exception:  # noqa: BLE001 - 降级：任何检索异常都不阻断对话
            return []
        return [
            KnowledgeChunk(
                text=_format(row[0], row[1], row[2]),
                department=row[1],
                score=float(row[3]),
            )
            for row in rows
        ]


def build_knowledge_retriever(settings: Settings) -> KnowledgeRetriever | None:
    """生产装配：database_url 或 embedding 模型未配置时返回 None（检索降级）。"""
    if not settings.database_url or not settings.doubao_embedding_model:
        return None
    from app.core.embeddings import build_embedding_model  # 延迟导入，避免无 embedding 配置时副作用

    contract = get_contracts().knowledge
    return PgvectorKnowledgeRetriever(
        dsn=settings.database_url,
        embedder=build_embedding_model(settings),
        top_k=contract.search_top_k,
        threshold=contract.similarity_threshold,
    )

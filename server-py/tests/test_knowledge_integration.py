"""知识检索集成测试（ADR-0010）：连真实 PG+pgvector+方舟 endpoint。

验收标准：固定 10 条典型症状查询，Top-3 命中期望知识块不少于 8 条。
需真实 .env 凭据（DATABASE_URL + ARK_API_KEY + DOUBAO_EMBEDDING_MODEL）与
已回填向量的 knowledge_chunks 表；无凭据时自动跳过（不进 CI）。
query 向量缓存：同一查询的 embed 只调一次 endpoint。
"""

import asyncio

import pytest

from app.config import get_settings
from app.core.eventloop import force_selector_event_loop_on_windows
from app.services.knowledge import PgvectorKnowledgeRetriever, build_knowledge_retriever

# Windows psycopg 异步需 SelectorEventLoop
force_selector_event_loop_on_windows()

# 10 条典型症状查询及期望命中的知识块 title（Top-3 内含期望块即算命中）
_QUERIES: list[tuple[str, str]] = [
    ("最近总觉得胸闷气短", "胸闷气短"),
    ("心跳很快心慌", "心悸心跳快"),
    ("血压有点高", "血压偏高"),
    ("咳嗽好几天了", "咳嗽"),
    ("反酸烧心难受", "反酸烧心"),
    ("经常头痛", "头痛"),
    ("最近特别口渴喝很多水", "口渴多饮"),
    ("皮肤起红疹很痒", "湿疹"),
    ("腰痛好久了", "腰痛"),
    ("眼睛干涩看屏幕难受", "眼睛干涩"),
]


def _integration_ready() -> bool:
    settings = get_settings()
    return bool(settings.database_url and settings.ark_api_key and settings.doubao_embedding_model)


pytestmark = pytest.mark.skipif(
    not _integration_ready(),
    reason="集成测试需真实 .env 凭据（DATABASE_URL/ARK_API_KEY/DOUBAO_EMBEDDING_MODEL）",
)


@pytest.fixture(scope="module")
def retriever() -> PgvectorKnowledgeRetriever:
    r = build_knowledge_retriever(get_settings())
    assert r is not None, "检索器构建失败（检查配置）"
    return r  # type: ignore[return-value]


def test_top3_hit_rate_at_least_8_of_10(retriever: PgvectorKnowledgeRetriever) -> None:
    """10 条查询 Top-3 命中期望知识块不少于 8 条（ADR-0010 验收标准）。"""
    hits = 0
    for query, expected_title in _QUERIES:
        results = asyncio.run(retriever.search(query))
        top3_titles = [r.text.split("】")[0].removeprefix("【").split("·")[0] for r in results[:3]]
        if expected_title in top3_titles:
            hits += 1
        else:
            print(f"  未命中: query={query!r} expected={expected_title!r} got={top3_titles}")
    print(f"\nTop-3 命中: {hits}/10")
    assert hits >= 8, f"Top-3 命中 {hits}/10，未达 ≥8 验收标准"


def test_recall_chunk_format(retriever: PgvectorKnowledgeRetriever) -> None:
    """召回块格式为【标题·科室】正文。"""
    results = asyncio.run(retriever.search("胸闷气短"))
    assert len(results) > 0
    assert results[0].text.startswith("【")
    assert "·" in results[0].text.split("】")[0]
    assert results[0].department

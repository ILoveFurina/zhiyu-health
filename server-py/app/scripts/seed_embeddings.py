"""离线 embedding 生成工具（ADR-0010）：读 knowledge_chunks 文本 -> 调 doubao-embedding-vision
-> 产出可审查、可版本化的 seed-knowledge.sql（向量回填），纳入幂等 seed 流程。

属离线数据准备，不在运行时调用：运行时 server-py 对 pgvector 只读检索，
不直写向量。产物 server-java/src/main/resources/seed-knowledge.sql 被 .gitignore
忽略（向量需真实凭据产出），由 Spring optional: data-locations 在存在时执行。

运行：uv run python -m app.scripts.seed_embeddings
"""

import asyncio
import sys
from pathlib import Path

from app.config import get_settings
from app.core.contracts import get_contracts
from app.core.embeddings import build_embedding_model
from app.db.clients import acquire_pg_connection

# 产物路径：server-java 的 classpath 资源目录（parents[3] = 仓库根 zhiyu-health）
_OUTPUT = (
    Path(__file__).resolve().parents[3]
    / "server-java"
    / "src"
    / "main"
    / "resources"
    / "seed-knowledge.sql"
)


async def _load_chunks(dsn: str) -> list[tuple[int, str, str, str]]:
    """读取全部 knowledge_chunks 文本（id, department, title, content）。"""
    conn = await acquire_pg_connection(dsn)
    try:
        cur = await conn.execute(
            "SELECT id, department, title, content FROM knowledge_chunks ORDER BY id"
        )
        rows = await cur.fetchall()
    finally:
        await conn.close()
    return [(r[0], r[1], r[2], r[3]) for r in rows]


def _vector_literal(values: list[float]) -> str:
    # pgvector 接受 '[v1,v2,...]' 文本字面量；保留足够精度
    return "[" + ",".join(f"{v:.8f}" for v in values) + "]"


async def main() -> int:
    settings = get_settings()
    if not settings.database_url:
        print("DATABASE_URL 未配置，无法读取 knowledge_chunks 文本", file=sys.stderr)
        return 1
    if not settings.doubao_embedding_model:
        print("DOUBAO_EMBEDDING_MODEL 未配置，无法调用 embedding endpoint", file=sys.stderr)
        return 1

    contract_dim = get_contracts().knowledge.embedding_dimension
    chunks = await _load_chunks(settings.database_url)
    if not chunks:
        print("knowledge_chunks 表为空，请先执行 schema.sql + seed.sql 入库文本", file=sys.stderr)
        return 1
    print(f"读取到 {len(chunks)} 条知识文本，开始向量化（维度期望 {contract_dim}）...")

    embedder = build_embedding_model(settings)
    # 用 拼接文本作为 embedding 输入：title + content 携带科室语义
    texts = [f"{title}。{content}" for _, _, title, content in chunks]
    # 方舟 embedding endpoint 限制单次最多 10 条输入，分批调用
    vectors: list[list[float]] = []
    batch_size = 10
    for i in range(0, len(texts), batch_size):
        vectors.extend(await embedder.aembed_documents(texts[i : i + batch_size]))

    actual_dim = len(vectors[0])
    if actual_dim != contract_dim:
        print(
            f"维度不一致：endpoint 返回 {actual_dim}，契约/DDL 为 {contract_dim}。"
            "请同步改 DDL vector(N)、application.yml 与 contracts/knowledge.json 后重跑。",
            file=sys.stderr,
        )
        return 1

    # 幂等 UPDATE：向量列写死 vector(1024)，文本已在 seed.sql 入库
    lines = [
        "-- 由 app.scripts.seed_embeddings 离线产出（连真实方舟），含向量不入库",
        "-- 幂等：重复执行覆盖同值；文本 seed 在 seed.sql，此处只回填 vector 列",
        "-- 维度路径 B：vector(1024)，与 DDL/契约/配置同源",
        "",
    ]
    for (chunk_id, _dept, _title, _content), vec in zip(chunks, vectors, strict=True):
        lines.append(
            f"UPDATE knowledge_chunks SET vector = '{_vector_literal(vec)}' "
            f"WHERE id = {chunk_id};"
        )
    _OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"已写出 {_OUTPUT}（{len(chunks)} 条向量回填）")
    return 0


if __name__ == "__main__":
    # Windows 默认 ProactorEventLoop 与 psycopg 异步不兼容
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    raise SystemExit(asyncio.run(main()))

"""server-java 调用的知识文档在线 embedding 接口（ADR-0036）。

本端点退化为纯批量 embedding 服务：输入文本列表、输出向量列表，零业务语义。
不触碰 knowledge_chunks 表（不读不写），只是把离线脚本 seed_embeddings.py 的
``aembed_documents`` 能力以 HTTP 服务形式暴露。拼接格式 ``f"{title}。{content}"``
与离线脚本完全一致，保证上传 chunk 与 seed chunk 处于同一 embedding 空间。
"""

from fastapi import APIRouter, HTTPException, Request
from openai import APITimeoutError

from app.api.deps import AgentCallbackAuth
from app.core.contracts import get_contracts
from app.schemas.embeddings import EmbedRequest, EmbedResponse

router = APIRouter(prefix="/agent/knowledge", tags=["agent-knowledge-embedding"])


def _error_detail(code: str) -> dict[str, str]:
    # 错误码与文案来自 contracts/knowledge-documents.json，server-java 出口消费白名单
    messages = {
        "KNOWLEDGE_EMBEDDING_INVALID": "知识文本参数不合法",
        "EMBEDDING_MODEL_TIMEOUT": "向量计算服务响应超时",
        "EMBEDDING_MODEL_FAILED": "向量计算服务暂不可用",
    }
    return {"code": code, "message": messages[code]}


@router.post("/embeddings")
async def create_embeddings(
    body: EmbedRequest, request: Request, _: AgentCallbackAuth
) -> EmbedResponse:
    embedder = request.app.state.knowledge_embedder
    # 拼接格式与离线脚本 seed_embeddings.py 完全一致：title + 中文句号 + content
    texts = [f"{item.title}。{item.content}" for item in body.texts]
    contract = get_contracts().knowledge_documents
    batch_size = contract.embedding.batch_size
    expected_dim = get_contracts().knowledge.embedding_dimension

    vectors: list[list[float]] = []
    try:
        for i in range(0, len(texts), batch_size):
            vectors.extend(await embedder.aembed_documents(texts[i : i + batch_size]))
    except (APITimeoutError, TimeoutError) as exc:
        raise HTTPException(status_code=504, detail=_error_detail("EMBEDDING_MODEL_TIMEOUT")) from exc
    except (RuntimeError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=_error_detail("EMBEDDING_MODEL_FAILED")) from exc

    if not vectors or len(vectors[0]) != expected_dim:
        # 维度不匹配：上传 chunk 与 seed chunk 不在同一 embedding 空间，必须拒绝
        raise HTTPException(status_code=502, detail=_error_detail("EMBEDDING_MODEL_FAILED"))

    return EmbedResponse(vectors=vectors)

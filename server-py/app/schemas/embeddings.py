"""知识文档在线 embedding 契约（ADR-0036）。

server-java 切分文档后调本端点批量计算向量；本端点不触碰 knowledge_chunks 表，
只做模型计算委托，与离线脚本 seed_embeddings.py 共用 build_embedding_model 与
``f"{title}。{content}"`` 拼接格式，保证上传 chunk 与 seed chunk 处于同一 embedding 空间。
"""

from pydantic import BaseModel, ConfigDict, Field


class EmbedTextItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1, max_length=200)
    content: str = Field(min_length=1, max_length=8000)


class EmbedRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    texts: list[EmbedTextItem] = Field(min_length=1, max_length=50)


class EmbedResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    vectors: list[list[float]]

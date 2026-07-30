"""Embedding 客户端装配：火山方舟 OpenAIEmbeddings 的唯一构建点（ADR-0004/0010）。

对话（agent.runner）用 build_chat_model；知识检索（services.knowledge）与离线
seed 工具用此处装配的 embedding 模型。一个 ARK_API_KEY 覆盖对话/视觉/embedding。
"""

from langchain_openai import OpenAIEmbeddings
from pydantic import SecretStr

from app.config import Settings


def build_embedding_model(settings: Settings) -> OpenAIEmbeddings:
    # check_embedding_ctx_length=False：方舟 embedding endpoint 不走 tiktoken 分词，
    # 交由服务端处理文本；True 时 langchain 会本地分词并传 token id 数组导致 400。
    return OpenAIEmbeddings(
        model=settings.doubao_embedding_model,
        base_url=settings.ark_base_url,
        api_key=SecretStr(settings.ark_api_key),
        check_embedding_ctx_length=False,
    )

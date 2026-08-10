"""知识文档在线 embedding 的懒构造适配器（ADR-0036）。

首次调用才经 build_embedding_model 构建真实 embedder，避免测试装配路径
（未配置方舟环境变量）因 import 期构造而失败。生产由 bootstrap 注入本类型，
测试可注入 fake embedder（含 aembed_documents 方法）替代。
"""

from app.core.embeddings import build_embedding_model
from app.core.lazy import LazyDelegate


class LazyKnowledgeEmbedder:
    """懒 embedder：首次 get() 才读 settings 构建方舟 embedder 并缓存。"""

    def __init__(self) -> None:
        self._delegate = LazyDelegate(lambda: build_embedding_model(_load_settings()))

    async def aembed_documents(self, texts: list[str]) -> list[list[float]]:
        return await self._delegate.get().aembed_documents(texts)


def _load_settings():
    from app.config import get_settings

    return get_settings()

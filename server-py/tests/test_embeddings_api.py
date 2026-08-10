"""知识文档在线 embedding HTTP seam；fake 替换真实方舟 embedder。

断言：分批 10、输入拼接 ``title。content``、维度不匹配 502、回调鉴权 401、输入校验 422。
"""

from conftest import TEST_AGENT_SECRET, StubHealthService
from fastapi.testclient import TestClient

from app.testing import create_test_app

_HEADERS = {"X-Agent-Callback-Token": TEST_AGENT_SECRET}
_DIM = 2048


class FakeEmbedder:
    """记录调用、可配置维度与异常的 fake embedder。"""

    def __init__(self, *, dim: int = _DIM, raises: Exception | None = None) -> None:
        self._dim = dim
        self._raises = raises
        self.calls: list[list[str]] = []

    async def aembed_documents(self, texts: list[str]) -> list[list[float]]:
        self.calls.append(list(texts))
        if self._raises is not None:
            raise self._raises
        return [[0.1] * self._dim for _ in texts]


def _app_with(embedder) -> TestClient:
    app = create_test_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        knowledge_embedder=embedder,
    )
    return TestClient(app)


def test_embeddings_concatenates_title_and_content() -> None:
    fake = FakeEmbedder()
    with _app_with(fake) as client:
        response = client.post(
            "/api/agent/knowledge/embeddings",
            json={
                "texts": [
                    {"title": "高血压护理 - 第1段", "content": "低盐饮食有助于控制血压。"},
                    {"title": "高血压护理 - 第2段", "content": "规律运动可改善心血管健康。"},
                ]
            },
            headers=_HEADERS,
        )
    assert response.status_code == 200
    vectors = response.json()["vectors"]
    assert len(vectors) == 2
    assert len(vectors[0]) == _DIM
    # 拼接格式必须与 seed_embeddings.py 完全一致：title。content
    assert fake.calls == [
        ["高血压护理 - 第1段。低盐饮食有助于控制血压。", "高血压护理 - 第2段。规律运动可改善心血管健康。"]
    ]


def test_embeddings_batches_in_groups_of_ten() -> None:
    # 12 条输入应分 2 批：10 + 2，与 seed_embeddings.py 的 batch_size=10 一致
    fake = FakeEmbedder()
    texts = [
        {"title": f"文档 - 第{i}段", "content": f"内容{i}"} for i in range(1, 13)
    ]
    with _app_with(fake) as client:
        response = client.post(
            "/api/agent/knowledge/embeddings",
            json={"texts": texts},
            headers=_HEADERS,
        )
    assert response.status_code == 200
    assert len(response.json()["vectors"]) == 12
    assert len(fake.calls) == 2
    assert len(fake.calls[0]) == 10
    assert len(fake.calls[1]) == 2


def test_embeddings_dimension_mismatch_returns_502() -> None:
    # 维度不匹配：上传 chunk 与 seed chunk 不在同一 embedding 空间，必须拒绝
    fake = FakeEmbedder(dim=1024)
    with _app_with(fake) as client:
        response = client.post(
            "/api/agent/knowledge/embeddings",
            json={"texts": [{"title": "标题", "content": "内容"}]},
            headers=_HEADERS,
        )
    assert response.status_code == 502
    assert response.json()["detail"]["code"] == "EMBEDDING_MODEL_FAILED"


def test_embeddings_model_timeout_returns_504() -> None:
    from openai import APITimeoutError

    fake = FakeEmbedder(raises=APITimeoutError(request=None))
    with _app_with(fake) as client:
        response = client.post(
            "/api/agent/knowledge/embeddings",
            json={"texts": [{"title": "标题", "content": "内容"}]},
            headers=_HEADERS,
        )
    assert response.status_code == 504
    assert response.json()["detail"]["code"] == "EMBEDDING_MODEL_TIMEOUT"


def test_embeddings_model_failure_returns_502() -> None:
    fake = FakeEmbedder(raises=RuntimeError("方舟 embedding 失败"))
    with _app_with(fake) as client:
        response = client.post(
            "/api/agent/knowledge/embeddings",
            json={"texts": [{"title": "标题", "content": "内容"}]},
            headers=_HEADERS,
        )
    assert response.status_code == 502
    assert response.json()["detail"]["code"] == "EMBEDDING_MODEL_FAILED"


def test_embeddings_requires_callback_token() -> None:
    fake = FakeEmbedder()
    with _app_with(fake) as client:
        response = client.post(
            "/api/agent/knowledge/embeddings",
            json={"texts": [{"title": "标题", "content": "内容"}]},
        )
    assert response.status_code == 401
    # 鉴权失败在 embedder 调用前，fake 不应被触及
    assert fake.calls == []


def test_embeddings_rejects_empty_texts() -> None:
    fake = FakeEmbedder()
    with _app_with(fake) as client:
        response = client.post(
            "/api/agent/knowledge/embeddings",
            json={"texts": []},
            headers=_HEADERS,
        )
    assert response.status_code == 422


def test_embeddings_rejects_extra_fields() -> None:
    fake = FakeEmbedder()
    with _app_with(fake) as client:
        response = client.post(
            "/api/agent/knowledge/embeddings",
            json={"texts": [{"title": "标题", "content": "内容", "extra": "bad"}]},
            headers=_HEADERS,
        )
    assert response.status_code == 422

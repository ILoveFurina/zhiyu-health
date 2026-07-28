"""C 端免注册 mock 登录（HTTP seam）。"""

from types import SimpleNamespace

from conftest import auth_headers, login


def test_mock_login_returns_token_and_patient(harness: SimpleNamespace) -> None:
    response = harness.client.post("/api/c/auth/mock-login", json={"nickname": "阿珍"})

    assert response.status_code == 200
    body = response.json()
    assert body["token"]
    assert body["patient"]["nickname"] == "阿珍"
    assert body["patient"]["id"] > 0


def test_mock_login_uses_default_nickname(harness: SimpleNamespace) -> None:
    response = harness.client.post("/api/c/auth/mock-login", json={})

    assert response.status_code == 200
    assert response.json()["patient"]["nickname"]


def test_mock_login_is_idempotent_for_same_nickname(harness: SimpleNamespace) -> None:
    first = harness.client.post("/api/c/auth/mock-login", json={"nickname": "阿强"}).json()
    second = harness.client.post("/api/c/auth/mock-login", json={"nickname": "阿强"}).json()

    assert first["patient"]["id"] == second["patient"]["id"]


def test_protected_endpoint_rejects_missing_token(harness: SimpleNamespace) -> None:
    response = harness.client.get("/api/c/conversations/1/messages")

    assert response.status_code == 401


def test_protected_endpoint_rejects_bad_token(harness: SimpleNamespace) -> None:
    response = harness.client.get(
        "/api/c/conversations/1/messages", headers=auth_headers("not-a-token")
    )

    assert response.status_code == 401


def test_issued_token_passes_auth(harness: SimpleNamespace) -> None:
    token = login(harness.client)

    response = harness.client.get(
        "/api/c/conversations/999/messages", headers=auth_headers(token)
    )

    # 会话不存在返回 404 而非 401，说明鉴权通过
    assert response.status_code == 404

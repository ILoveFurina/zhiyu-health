from fastapi.testclient import TestClient

from app.main import app, create_app


class StubHealthService:
    async def check(self) -> dict[str, object]:
        return {
            "status": "ok",
            "services": {
                "postgres": {"status": "ok"},
                "redis": {"status": "ok"},
                "neo4j": {"status": "ok"},
            },
        }


def test_health_reports_all_storage_dependencies() -> None:
    with TestClient(create_app(health_service=StubHealthService())) as client:
        response = client.get("/api/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "services": {
            "postgres": {"status": "ok"},
            "redis": {"status": "ok"},
            "neo4j": {"status": "ok"},
        },
    }


def test_default_application_is_ready_for_asgi_server() -> None:
    with TestClient(app) as client:
        response = client.get("/openapi.json")

    assert response.status_code == 200
    assert response.json()["info"]["title"] == "智愈 API"

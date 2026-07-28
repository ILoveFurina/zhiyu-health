from fastapi.testclient import TestClient
from sqlalchemy import create_engine

from app.main import create_app


def test_admin_can_log_in_and_access_staff_profile(tmp_path) -> None:
    engine = create_engine(f"sqlite:///{tmp_path / 'auth.db'}")
    app = create_app(database_engine=engine, seed_database=True)

    with TestClient(app) as client:
        login = client.post(
            "/api/b/auth/login",
            json={"username": "admin", "password": "admin123"},
        )
        profile = client.get(
            "/api/b/auth/me",
            headers={"Authorization": f"Bearer {login.json()['access_token']}"},
        )

    assert login.status_code == 200
    assert login.json()["token_type"] == "bearer"
    assert profile.status_code == 200
    assert profile.json() == {"username": "admin", "role": "admin", "doctor_id": None}

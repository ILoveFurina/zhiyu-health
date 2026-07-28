import secrets
from datetime import UTC, datetime, timedelta

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event
from sqlalchemy.orm import Session

from app.main import create_app


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, int] = {}

    async def set(self, key: str, value: int) -> None:
        self.values[key] = value

    async def delete(self, key: str) -> None:
        self.values.pop(key, None)


def create_admin_client(tmp_path) -> tuple[TestClient, dict[str, str], FakeRedis]:
    admin_password = secrets.token_urlsafe(16)
    redis = FakeRedis()
    engine = create_engine(f"sqlite:///{tmp_path / 'schedules.db'}")
    client = TestClient(
        create_app(
            database_engine=engine,
            redis_client=redis,
            seed_database=True,
            seed_admin_password=admin_password,
            seed_doctor_password=secrets.token_urlsafe(16),
        ),
        raise_server_exceptions=False,
    )
    client.__enter__()
    login = client.post(
        "/api/b/auth/login", json={"username": "admin", "password": admin_password}
    )
    return client, {"Authorization": f"Bearer {login.json()['access_token']}"}, redis


def test_admin_can_create_and_query_schedule_with_initialized_slot_count(tmp_path) -> None:
    client, headers, redis = create_admin_client(tmp_path)
    schedule_date = (datetime.now(UTC).date() + timedelta(days=1)).isoformat()
    try:
        doctor_id = client.get("/api/b/doctors", headers=headers).json()[0]["id"]
        created = client.post(
            "/api/b/schedules",
            headers=headers,
            json={
                "doctor_id": doctor_id,
                "schedule_date": schedule_date,
                "time_slot": "上午",
                "total_slots": 20,
            },
        )
        listed = client.get("/api/b/schedules", headers=headers)
    finally:
        client.__exit__(None, None, None)

    assert created.status_code == 201
    assert created.json() == {
        "id": created.json()["id"],
        "doctor_id": doctor_id,
        "schedule_date": schedule_date,
        "time_slot": "上午",
        "total_slots": 20,
        "remaining_slots": 20,
        "is_active": True,
    }
    assert listed.json() == [created.json()]
    assert redis.values[f"schedule:{created.json()['id']}:remaining_slots"] == 20


def test_admin_can_disable_schedule_without_removing_its_slot_pool(tmp_path) -> None:
    client, headers, redis = create_admin_client(tmp_path)
    try:
        doctor_id = client.get("/api/b/doctors", headers=headers).json()[0]["id"]
        created = client.post(
            "/api/b/schedules",
            headers=headers,
            json={
                "doctor_id": doctor_id,
                "schedule_date": (
                    datetime.now(UTC).date() + timedelta(days=1)
                ).isoformat(),
                "time_slot": "下午",
                "total_slots": 12,
            },
        ).json()
        disabled = client.patch(
            f"/api/b/schedules/{created['id']}/disable", headers=headers
        )
        missing = client.patch("/api/b/schedules/999/disable", headers=headers)
        listed = client.get("/api/b/schedules", headers=headers)
    finally:
        client.__exit__(None, None, None)

    assert disabled.status_code == 200
    assert disabled.json() == {**created, "is_active": False}
    assert listed.json() == [disabled.json()]
    assert missing.status_code == 404
    assert redis.values[f"schedule:{created['id']}:remaining_slots"] == 12


def test_failed_database_commit_does_not_leave_orphaned_slot_count(tmp_path) -> None:
    client, headers, redis = create_admin_client(tmp_path)

    def fail_commit(_: Session) -> None:
        raise RuntimeError("模拟数据库提交失败")

    try:
        doctor_id = client.get("/api/b/doctors", headers=headers).json()[0]["id"]
        event.listen(Session, "before_commit", fail_commit)
        failed = client.post(
            "/api/b/schedules",
            headers=headers,
            json={
                "doctor_id": doctor_id,
                "schedule_date": (
                    datetime.now(UTC).date() + timedelta(days=1)
                ).isoformat(),
                "time_slot": "上午",
                "total_slots": 8,
            },
        )
        event.remove(Session, "before_commit", fail_commit)
        listed = client.get("/api/b/schedules", headers=headers)
    finally:
        if event.contains(Session, "before_commit", fail_commit):
            event.remove(Session, "before_commit", fail_commit)
        client.__exit__(None, None, None)

    assert failed.status_code == 500
    assert listed.json() == []
    assert redis.values == {}


def test_schedule_rejects_time_slot_outside_supported_periods(tmp_path) -> None:
    client, headers, redis = create_admin_client(tmp_path)
    try:
        doctor_id = client.get("/api/b/doctors", headers=headers).json()[0]["id"]
        rejected = client.post(
            "/api/b/schedules",
            headers=headers,
            json={
                "doctor_id": doctor_id,
                "schedule_date": (
                    datetime.now(UTC).date() + timedelta(days=1)
                ).isoformat(),
                "time_slot": "凌晨",
                "total_slots": 8,
            },
        )
        listed = client.get("/api/b/schedules", headers=headers)
    finally:
        client.__exit__(None, None, None)

    assert rejected.status_code == 422
    assert listed.json() == []
    assert redis.values == {}

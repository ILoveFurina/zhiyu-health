from fastapi.testclient import TestClient
from sqlalchemy import create_engine

from app.main import create_app


def create_admin_client(tmp_path) -> tuple[TestClient, dict[str, str]]:
    engine = create_engine(f"sqlite:///{tmp_path / 'organization.db'}")
    client = TestClient(create_app(database_engine=engine, seed_database=True))
    client.__enter__()
    login = client.post(
        "/api/b/auth/login", json={"username": "admin", "password": "admin123"}
    )
    return client, {"Authorization": f"Bearer {login.json()['access_token']}"}


def test_admin_can_manage_hospitals_through_full_crud(tmp_path) -> None:
    client, headers = create_admin_client(tmp_path)
    try:
        assert client.get("/api/b/hospitals").status_code == 401

        created = client.post(
            "/api/b/hospitals",
            headers=headers,
            json={
                "name": "智愈市第一医院",
                "level": "三级甲等",
                "address": "健康路 1 号",
                "longitude": 121.4737,
                "latitude": 31.2304,
            },
        )
        hospital_id = created.json()["id"]
        listed = client.get("/api/b/hospitals", headers=headers)
        updated = client.put(
            f"/api/b/hospitals/{hospital_id}",
            headers=headers,
            json={
                "name": "智愈市中心医院",
                "level": "三级甲等",
                "address": "康复路 2 号",
                "longitude": 121.48,
                "latitude": 31.24,
            },
        )
        deleted = client.delete(f"/api/b/hospitals/{hospital_id}", headers=headers)
        after_delete = client.get("/api/b/hospitals", headers=headers)
    finally:
        client.__exit__(None, None, None)

    assert created.status_code == 201
    assert created.json() in listed.json()
    assert updated.status_code == 200
    assert updated.json()["name"] == "智愈市中心医院"
    assert deleted.status_code == 204
    assert created.json() not in after_delete.json()


def create_hospital(client: TestClient, headers: dict[str, str]) -> int:
    response = client.post(
        "/api/b/hospitals",
        headers=headers,
        json={
            "name": "智愈市第一医院",
            "level": "三级甲等",
            "address": "健康路 1 号",
            "longitude": 121.4737,
            "latitude": 31.2304,
        },
    )
    return response.json()["id"]


def test_admin_can_manage_departments_with_visit_location(tmp_path) -> None:
    client, headers = create_admin_client(tmp_path)
    try:
        hospital_id = create_hospital(client, headers)
        created = client.post(
            "/api/b/departments",
            headers=headers,
            json={
                "hospital_id": hospital_id,
                "name": "心内科",
                "floor": "门诊楼 3 层",
                "location": "东区 301 室",
            },
        )
        department_id = created.json()["id"]
        listed = client.get("/api/b/departments", headers=headers)
        updated = client.put(
            f"/api/b/departments/{department_id}",
            headers=headers,
            json={
                "hospital_id": hospital_id,
                "name": "心血管内科",
                "floor": "门诊楼 4 层",
                "location": "东区 401 室",
            },
        )
        deleted = client.delete(f"/api/b/departments/{department_id}", headers=headers)
    finally:
        client.__exit__(None, None, None)

    assert created.status_code == 201
    assert created.json() in listed.json()
    assert updated.json()["floor"] == "门诊楼 4 层"
    assert updated.json()["location"] == "东区 401 室"
    assert deleted.status_code == 204


def test_admin_can_manage_doctors_with_professional_profile(tmp_path) -> None:
    client, headers = create_admin_client(tmp_path)
    try:
        hospital_id = create_hospital(client, headers)
        department = client.post(
            "/api/b/departments",
            headers=headers,
            json={
                "hospital_id": hospital_id,
                "name": "心内科",
                "floor": "门诊楼 3 层",
                "location": "东区 301 室",
            },
        ).json()
        created = client.post(
            "/api/b/doctors",
            headers=headers,
            json={
                "department_id": department["id"],
                "name": "林知远",
                "title": "主任医师",
                "specialty": "高血压、冠心病",
                "photo_url": "https://example.com/doctor-lin.jpg",
            },
        )
        doctor_id = created.json()["id"]
        listed = client.get("/api/b/doctors", headers=headers)
        updated = client.put(
            f"/api/b/doctors/{doctor_id}",
            headers=headers,
            json={
                "department_id": department["id"],
                "name": "林知远",
                "title": "主任医师",
                "specialty": "高血压、冠心病、心律失常",
                "photo_url": "https://example.com/doctor-lin-new.jpg",
            },
        )
        deleted = client.delete(f"/api/b/doctors/{doctor_id}", headers=headers)
    finally:
        client.__exit__(None, None, None)

    assert created.status_code == 201
    assert created.json() in listed.json()
    assert updated.json()["specialty"] == "高血压、冠心病、心律失常"
    assert deleted.status_code == 204


def test_seed_exposes_minimum_organization_and_separates_doctor_role(tmp_path) -> None:
    client, admin_headers = create_admin_client(tmp_path)
    try:
        hospitals = client.get("/api/b/hospitals", headers=admin_headers)
        departments = client.get("/api/b/departments", headers=admin_headers)
        doctors = client.get("/api/b/doctors", headers=admin_headers)
        doctor_login = client.post(
            "/api/b/auth/login",
            json={"username": "doctor.lin", "password": "doctor123"},
        )
        doctor_headers = {
            "Authorization": f"Bearer {doctor_login.json()['access_token']}"
        }
        forbidden = client.get("/api/b/hospitals", headers=doctor_headers)
        profile = client.get("/api/b/auth/me", headers=doctor_headers)
    finally:
        client.__exit__(None, None, None)

    assert len(hospitals.json()) == 1
    assert len(departments.json()) == 2
    assert len(doctors.json()) == 3
    assert doctor_login.status_code == 200
    assert forbidden.status_code == 403
    assert profile.json()["role"] == "doctor"
    assert profile.json()["doctor_id"] == doctors.json()[0]["id"]

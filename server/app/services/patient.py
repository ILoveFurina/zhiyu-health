"""C 端 mock 登录与患者令牌（免注册即得患者身份，登录本身为 Mock 边界）。

与 B 端员工认证（app.services.auth.AuthService）是独立账号体系；
令牌用 scope 声明区分，避免两端令牌混用。
"""

from datetime import UTC, datetime, timedelta

import jwt
from sqlalchemy import select
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from app.models import Patient

PATIENT_TOKEN_SCOPE = "c_patient"


class PatientTokenService:
    def __init__(self, secret: str, expire_minutes: int = 720) -> None:
        self._secret = secret
        self._expire_minutes = expire_minutes

    def issue(self, patient_id: int) -> str:
        now = datetime.now(UTC)
        payload = {
            "sub": str(patient_id),
            "scope": PATIENT_TOKEN_SCOPE,
            "iat": now,
            "exp": now + timedelta(minutes=self._expire_minutes),
        }
        return jwt.encode(payload, self._secret, algorithm="HS256")

    def verify(self, token: str) -> int:
        """校验失败抛 jwt.InvalidTokenError，由调用方转换为 401。"""
        payload = jwt.decode(token, self._secret, algorithms=["HS256"])
        if payload.get("scope") != PATIENT_TOKEN_SCOPE:
            raise jwt.InvalidTokenError("令牌域不符")
        return int(payload["sub"])


class PatientService:
    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def mock_login(self, nickname: str) -> Patient:
        """免注册登录：按昵称取或建患者。"""
        with Session(self._engine) as session:
            patient = session.scalar(select(Patient).where(Patient.nickname == nickname))
            if patient is None:
                patient = Patient(nickname=nickname)
                session.add(patient)
                session.commit()
                session.refresh(patient)
            return patient

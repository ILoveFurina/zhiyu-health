from datetime import UTC, datetime, timedelta

import jwt
from pwdlib import PasswordHash
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.staff import StaffUser

password_hash = PasswordHash.recommended()


class AuthService:
    def __init__(self, session: Session, jwt_secret: str) -> None:
        self.session = session
        self.jwt_secret = jwt_secret

    def authenticate(self, username: str, password: str) -> StaffUser | None:
        staff = self.session.scalar(select(StaffUser).where(StaffUser.username == username))
        if staff is None or not password_hash.verify(password, staff.password_hash):
            return None
        return staff

    def create_access_token(self, staff: StaffUser) -> str:
        expires_at = datetime.now(UTC) + timedelta(hours=8)
        return jwt.encode(
            {"sub": str(staff.id), "role": staff.role.value, "exp": expires_at},
            self.jwt_secret,
            algorithm="HS256",
        )

    def resolve_token(self, token: str) -> StaffUser | None:
        try:
            payload = jwt.decode(token, self.jwt_secret, algorithms=["HS256"])
            staff_id = int(payload["sub"])
        except (jwt.InvalidTokenError, KeyError, TypeError, ValueError):
            return None
        return self.session.get(StaffUser, staff_id)

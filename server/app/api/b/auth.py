from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.db.session import get_session
from app.models.staff import StaffUser
from app.schemas.auth import LoginRequest, StaffProfile, TokenResponse
from app.services.auth import AuthService

router = APIRouter(prefix="/b/auth", tags=["B 端认证"])
bearer_scheme = HTTPBearer(auto_error=False)


def get_auth_service(
    request: Request, session: Annotated[Session, Depends(get_session)]
) -> AuthService:
    return AuthService(session, request.app.state.jwt_secret)


def get_current_staff(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
    service: Annotated[AuthService, Depends(get_auth_service)],
) -> StaffUser:
    staff = service.resolve_token(credentials.credentials) if credentials else None
    if staff is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="登录已失效")
    return staff


@router.post("/login", response_model=TokenResponse)
def login(
    payload: LoginRequest, service: Annotated[AuthService, Depends(get_auth_service)]
) -> TokenResponse:
    staff = service.authenticate(payload.username, payload.password)
    if staff is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="账号或密码错误")
    return TokenResponse(access_token=service.create_access_token(staff))


@router.get("/me", response_model=StaffProfile)
def profile(staff: Annotated[StaffUser, Depends(get_current_staff)]) -> StaffUser:
    return staff

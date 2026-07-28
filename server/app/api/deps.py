"""HTTP 层公共依赖：C 端患者鉴权。"""

from typing import Annotated

import jwt
from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

bearer_scheme = HTTPBearer(auto_error=False)


def get_current_patient_id(
    request: Request,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
) -> int:
    if credentials is None:
        raise HTTPException(status_code=401, detail="缺少登录令牌")
    try:
        return request.app.state.patient_token_service.verify(credentials.credentials)  # type: ignore[no-any-return]
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=401, detail="登录令牌无效或已过期") from None

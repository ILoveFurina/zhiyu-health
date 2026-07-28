"""HTTP 层公共依赖：C 端患者鉴权。"""

import jwt
from fastapi import HTTPException, Request


def get_current_patient_id(request: Request) -> int:
    authorization = request.headers.get("Authorization", "")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise HTTPException(status_code=401, detail="缺少登录令牌")
    try:
        return request.app.state.token_service.verify(token)  # type: ignore[no-any-return]
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=401, detail="登录令牌无效或已过期") from None

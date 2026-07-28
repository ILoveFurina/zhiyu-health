from fastapi import APIRouter, Request

from app.schemas.health import HealthResponse
from app.services.health import HealthChecker

router = APIRouter(tags=["system"])


@router.get("/health", response_model=HealthResponse)
async def health(request: Request) -> dict[str, object]:
    checker: HealthChecker = request.app.state.health_service
    return await checker.check()

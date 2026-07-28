from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.api.b.organization import Admin
from app.db.session import get_session
from app.schemas.schedule import ScheduleInput, ScheduleResponse
from app.services.schedule import ScheduleService

router = APIRouter(prefix="/b/schedules", tags=["B 端排班管理"])


def get_schedule_service(
    request: Request, session: Annotated[Session, Depends(get_session)]
) -> ScheduleService:
    return ScheduleService(session, request.app.state.redis_client)


Service = Annotated[ScheduleService, Depends(get_schedule_service)]


@router.get("", response_model=list[ScheduleResponse])
def list_schedules(_: Admin, service: Service) -> list:
    return service.list_schedules()


@router.post("", response_model=ScheduleResponse, status_code=status.HTTP_201_CREATED)
async def create_schedule(payload: ScheduleInput, _: Admin, service: Service):
    schedule = await service.create_schedule(payload)
    if schedule is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="医生不存在")
    return schedule


@router.patch("/{schedule_id}/disable", response_model=ScheduleResponse)
def disable_schedule(schedule_id: int, _: Admin, service: Service):
    schedule = service.disable_schedule(schedule_id)
    if schedule is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="排班不存在")
    return schedule

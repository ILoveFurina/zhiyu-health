from typing import Protocol

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.organization import Doctor
from app.models.schedule import Schedule
from app.schemas.schedule import ScheduleInput


class SlotCounter(Protocol):
    async def set(self, key: str, value: int) -> object: ...

    async def delete(self, key: str) -> object: ...


def slot_counter_key(schedule_id: int) -> str:
    return f"schedule:{schedule_id}:remaining_slots"


class ScheduleService:
    def __init__(self, session: Session, slot_counter: SlotCounter) -> None:
        self.session = session
        self.slot_counter = slot_counter

    def list_schedules(self) -> list[Schedule]:
        return list(
            self.session.scalars(
                select(Schedule).order_by(Schedule.schedule_date, Schedule.time_slot, Schedule.id)
            )
        )

    async def create_schedule(self, payload: ScheduleInput) -> Schedule | None:
        if self.session.get(Doctor, payload.doctor_id) is None:
            return None
        schedule = Schedule(
            **payload.model_dump(),
            remaining_slots=payload.total_slots,
            is_active=True,
        )
        self.session.add(schedule)
        self.session.flush()
        counter_key = slot_counter_key(schedule.id)
        try:
            await self.slot_counter.set(counter_key, schedule.remaining_slots)
            self.session.commit()
        except Exception:
            self.session.rollback()
            await self.slot_counter.delete(counter_key)
            raise
        self.session.refresh(schedule)
        return schedule

    def disable_schedule(self, schedule_id: int) -> Schedule | None:
        schedule = self.session.get(Schedule, schedule_id)
        if schedule is None:
            return None
        schedule.is_active = False
        self.session.commit()
        self.session.refresh(schedule)
        return schedule

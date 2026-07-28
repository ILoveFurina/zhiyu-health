from datetime import date

from pydantic import BaseModel, ConfigDict, Field

from app.models.schedule import TimeSlot


class ScheduleInput(BaseModel):
    doctor_id: int
    schedule_date: date
    time_slot: TimeSlot
    total_slots: int = Field(gt=0)


class ScheduleResponse(ScheduleInput):
    model_config = ConfigDict(from_attributes=True)

    id: int
    remaining_slots: int
    is_active: bool

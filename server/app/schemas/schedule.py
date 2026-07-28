from datetime import date

from pydantic import BaseModel, ConfigDict, Field


class ScheduleInput(BaseModel):
    doctor_id: int
    schedule_date: date
    time_slot: str = Field(min_length=1, max_length=30)
    total_slots: int = Field(gt=0)


class ScheduleResponse(ScheduleInput):
    model_config = ConfigDict(from_attributes=True)

    id: int
    remaining_slots: int
    is_active: bool

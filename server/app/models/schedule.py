from datetime import date
from enum import StrEnum
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, CheckConstraint, Date, Enum, ForeignKey, Integer
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base

if TYPE_CHECKING:
    from app.models.organization import Doctor


class TimeSlot(StrEnum):
    MORNING = "上午"
    AFTERNOON = "下午"
    EVENING = "晚上"


class Schedule(Base):
    __tablename__ = "schedules"
    __table_args__ = (
        CheckConstraint("total_slots > 0", name="ck_schedules_total_slots_positive"),
        CheckConstraint(
            "remaining_slots >= 0 AND remaining_slots <= total_slots",
            name="ck_schedules_remaining_slots_range",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    doctor_id: Mapped[int] = mapped_column(ForeignKey("doctors.id"))
    schedule_date: Mapped[date] = mapped_column(Date)
    time_slot: Mapped[TimeSlot] = mapped_column(
        Enum(
            TimeSlot,
            values_callable=lambda enum: [item.value for item in enum],
            native_enum=False,
            length=30,
        )
    )
    total_slots: Mapped[int] = mapped_column(Integer)
    remaining_slots: Mapped[int] = mapped_column(Integer)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    doctor: Mapped["Doctor"] = relationship(back_populates="schedules")

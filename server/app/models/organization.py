from sqlalchemy import Float, ForeignKey, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base


class Hospital(Base):
    __tablename__ = "hospitals"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100), unique=True)
    level: Mapped[str] = mapped_column(String(30))
    address: Mapped[str] = mapped_column(String(255))
    longitude: Mapped[float] = mapped_column(Float)
    latitude: Mapped[float] = mapped_column(Float)
    departments: Mapped[list["Department"]] = relationship(
        back_populates="hospital", cascade="all, delete-orphan"
    )


class Department(Base):
    __tablename__ = "departments"

    id: Mapped[int] = mapped_column(primary_key=True)
    hospital_id: Mapped[int] = mapped_column(ForeignKey("hospitals.id"))
    name: Mapped[str] = mapped_column(String(100))
    floor: Mapped[str] = mapped_column(String(30))
    location: Mapped[str] = mapped_column(String(255))
    hospital: Mapped[Hospital] = relationship(back_populates="departments")
    doctors: Mapped[list["Doctor"]] = relationship(
        back_populates="department", cascade="all, delete-orphan"
    )


class Doctor(Base):
    __tablename__ = "doctors"

    id: Mapped[int] = mapped_column(primary_key=True)
    department_id: Mapped[int] = mapped_column(ForeignKey("departments.id"))
    name: Mapped[str] = mapped_column(String(50))
    title: Mapped[str] = mapped_column(String(50))
    specialty: Mapped[str] = mapped_column(Text)
    photo_url: Mapped[str] = mapped_column(String(500))
    department: Mapped[Department] = relationship(back_populates="doctors")

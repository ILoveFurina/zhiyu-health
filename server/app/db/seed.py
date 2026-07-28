from pwdlib import PasswordHash
from sqlalchemy import Engine, select
from sqlalchemy.orm import Session

from app.models import Base, Department, Doctor, Hospital, StaffRole, StaffUser

password_hash = PasswordHash.recommended()


def initialize_database(engine: Engine, *, with_seed: bool) -> None:
    Base.metadata.create_all(engine)
    if with_seed:
        seed_database(engine)


def seed_database(engine: Engine) -> None:
    with Session(engine) as session:
        hospital = session.scalar(select(Hospital).where(Hospital.name == "智愈市人民医院"))
        if hospital is None:
            hospital = Hospital(
                name="智愈市人民医院",
                level="三级甲等",
                address="智愈市安康路 88 号",
                longitude=121.4737,
                latitude=31.2304,
            )
            session.add(hospital)
            session.flush()

        cardiology = _get_or_create_department(
            session, hospital, "心血管内科", "门诊楼 3 层", "东区 301 室"
        )
        dermatology = _get_or_create_department(
            session, hospital, "皮肤科", "门诊楼 2 层", "西区 205 室"
        )
        doctors = [
            _get_or_create_doctor(
                session,
                cardiology,
                "林知远",
                "主任医师",
                "高血压、冠心病、心律失常",
                "https://example.com/demo/lin-zhiyuan.jpg",
            ),
            _get_or_create_doctor(
                session,
                cardiology,
                "周安宁",
                "副主任医师",
                "胸痛评估、心力衰竭",
                "https://example.com/demo/zhou-anning.jpg",
            ),
            _get_or_create_doctor(
                session,
                dermatology,
                "陈清禾",
                "主治医师",
                "湿疹、荨麻疹、痤疮",
                "https://example.com/demo/chen-qinghe.jpg",
            ),
        ]
        if session.scalar(select(StaffUser).where(StaffUser.username == "admin")) is None:
            session.add(
                StaffUser(
                    username="admin",
                    password_hash=password_hash.hash("admin123"),
                    role=StaffRole.ADMIN,
                )
            )
        if session.scalar(select(StaffUser).where(StaffUser.username == "doctor.lin")) is None:
            session.add(
                StaffUser(
                    username="doctor.lin",
                    password_hash=password_hash.hash("doctor123"),
                    role=StaffRole.DOCTOR,
                    doctor_id=doctors[0].id,
                )
            )
        session.commit()


def _get_or_create_department(
    session: Session, hospital: Hospital, name: str, floor: str, location: str
) -> Department:
    department = session.scalar(
        select(Department).where(
            Department.hospital_id == hospital.id, Department.name == name
        )
    )
    if department is None:
        department = Department(
            hospital_id=hospital.id, name=name, floor=floor, location=location
        )
        session.add(department)
        session.flush()
    return department


def _get_or_create_doctor(
    session: Session,
    department: Department,
    name: str,
    title: str,
    specialty: str,
    photo_url: str,
) -> Doctor:
    doctor = session.scalar(
        select(Doctor).where(Doctor.department_id == department.id, Doctor.name == name)
    )
    if doctor is None:
        doctor = Doctor(
            department_id=department.id,
            name=name,
            title=title,
            specialty=specialty,
            photo_url=photo_url,
        )
        session.add(doctor)
        session.flush()
    return doctor

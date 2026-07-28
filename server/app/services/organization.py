from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.organization import Department, Doctor, Hospital
from app.schemas.organization import DepartmentInput, DoctorInput, HospitalInput


class OrganizationService:
    def __init__(self, session: Session) -> None:
        self.session = session

    def list_hospitals(self) -> list[Hospital]:
        return list(self.session.scalars(select(Hospital).order_by(Hospital.id)))

    def create_hospital(self, payload: HospitalInput) -> Hospital:
        hospital = Hospital(**payload.model_dump())
        self.session.add(hospital)
        self.session.commit()
        self.session.refresh(hospital)
        return hospital

    def update_hospital(self, hospital_id: int, payload: HospitalInput) -> Hospital | None:
        hospital = self.session.get(Hospital, hospital_id)
        if hospital is None:
            return None
        for field, value in payload.model_dump().items():
            setattr(hospital, field, value)
        self.session.commit()
        self.session.refresh(hospital)
        return hospital

    def delete_hospital(self, hospital_id: int) -> bool:
        hospital = self.session.get(Hospital, hospital_id)
        if hospital is None:
            return False
        self.session.delete(hospital)
        self.session.commit()
        return True

    def list_departments(self) -> list[Department]:
        return list(self.session.scalars(select(Department).order_by(Department.id)))

    def create_department(self, payload: DepartmentInput) -> Department | None:
        if self.session.get(Hospital, payload.hospital_id) is None:
            return None
        department = Department(**payload.model_dump())
        self.session.add(department)
        self.session.commit()
        self.session.refresh(department)
        return department

    def update_department(
        self, department_id: int, payload: DepartmentInput
    ) -> Department | None:
        department = self.session.get(Department, department_id)
        if department is None or self.session.get(Hospital, payload.hospital_id) is None:
            return None
        for field, value in payload.model_dump().items():
            setattr(department, field, value)
        self.session.commit()
        self.session.refresh(department)
        return department

    def delete_department(self, department_id: int) -> bool:
        department = self.session.get(Department, department_id)
        if department is None:
            return False
        self.session.delete(department)
        self.session.commit()
        return True

    def list_doctors(self) -> list[Doctor]:
        return list(self.session.scalars(select(Doctor).order_by(Doctor.id)))

    def create_doctor(self, payload: DoctorInput) -> Doctor | None:
        if self.session.get(Department, payload.department_id) is None:
            return None
        doctor = Doctor(**payload.model_dump())
        self.session.add(doctor)
        self.session.commit()
        self.session.refresh(doctor)
        return doctor

    def update_doctor(self, doctor_id: int, payload: DoctorInput) -> Doctor | None:
        doctor = self.session.get(Doctor, doctor_id)
        if doctor is None or self.session.get(Department, payload.department_id) is None:
            return None
        for field, value in payload.model_dump().items():
            setattr(doctor, field, value)
        self.session.commit()
        self.session.refresh(doctor)
        return doctor

    def delete_doctor(self, doctor_id: int) -> bool:
        doctor = self.session.get(Doctor, doctor_id)
        if doctor is None:
            return False
        self.session.delete(doctor)
        self.session.commit()
        return True

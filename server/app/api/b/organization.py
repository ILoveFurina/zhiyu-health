from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.orm import Session

from app.api.b.auth import get_current_staff
from app.db.session import get_session
from app.models.staff import StaffRole, StaffUser
from app.schemas.organization import (
    DepartmentInput,
    DepartmentResponse,
    DoctorInput,
    DoctorResponse,
    HospitalInput,
    HospitalResponse,
)
from app.services.organization import OrganizationService

router = APIRouter(prefix="/b", tags=["B 端组织管理"])


def require_admin(staff: Annotated[StaffUser, Depends(get_current_staff)]) -> StaffUser:
    if staff.role is not StaffRole.ADMIN:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="仅管理员可操作")
    return staff


def get_organization_service(
    session: Annotated[Session, Depends(get_session)],
) -> OrganizationService:
    return OrganizationService(session)


Admin = Annotated[StaffUser, Depends(require_admin)]
Service = Annotated[OrganizationService, Depends(get_organization_service)]


@router.get("/hospitals", response_model=list[HospitalResponse])
def list_hospitals(_: Admin, service: Service) -> list:
    return service.list_hospitals()


@router.post("/hospitals", response_model=HospitalResponse, status_code=status.HTTP_201_CREATED)
def create_hospital(payload: HospitalInput, _: Admin, service: Service):
    return service.create_hospital(payload)


@router.put("/hospitals/{hospital_id}", response_model=HospitalResponse)
def update_hospital(hospital_id: int, payload: HospitalInput, _: Admin, service: Service):
    hospital = service.update_hospital(hospital_id, payload)
    if hospital is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="医院不存在")
    return hospital


@router.delete("/hospitals/{hospital_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_hospital(hospital_id: int, _: Admin, service: Service) -> Response:
    if not service.delete_hospital(hospital_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="医院不存在")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/departments", response_model=list[DepartmentResponse])
def list_departments(_: Admin, service: Service) -> list:
    return service.list_departments()


@router.post(
    "/departments", response_model=DepartmentResponse, status_code=status.HTTP_201_CREATED
)
def create_department(payload: DepartmentInput, _: Admin, service: Service):
    department = service.create_department(payload)
    if department is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="医院不存在")
    return department


@router.put("/departments/{department_id}", response_model=DepartmentResponse)
def update_department(
    department_id: int, payload: DepartmentInput, _: Admin, service: Service
):
    department = service.update_department(department_id, payload)
    if department is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="科室或医院不存在")
    return department


@router.delete("/departments/{department_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_department(department_id: int, _: Admin, service: Service) -> Response:
    if not service.delete_department(department_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="科室不存在")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/doctors", response_model=list[DoctorResponse])
def list_doctors(_: Admin, service: Service) -> list:
    return service.list_doctors()


@router.post("/doctors", response_model=DoctorResponse, status_code=status.HTTP_201_CREATED)
def create_doctor(payload: DoctorInput, _: Admin, service: Service):
    doctor = service.create_doctor(payload)
    if doctor is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="科室不存在")
    return doctor


@router.put("/doctors/{doctor_id}", response_model=DoctorResponse)
def update_doctor(doctor_id: int, payload: DoctorInput, _: Admin, service: Service):
    doctor = service.update_doctor(doctor_id, payload)
    if doctor is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="医生或科室不存在")
    return doctor


@router.delete("/doctors/{doctor_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_doctor(doctor_id: int, _: Admin, service: Service) -> Response:
    if not service.delete_doctor(doctor_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="医生不存在")
    return Response(status_code=status.HTTP_204_NO_CONTENT)

from pydantic import BaseModel, ConfigDict, Field


class HospitalInput(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    level: str = Field(min_length=1, max_length=30)
    address: str = Field(min_length=1, max_length=255)
    longitude: float = Field(ge=-180, le=180)
    latitude: float = Field(ge=-90, le=90)


class HospitalResponse(HospitalInput):
    model_config = ConfigDict(from_attributes=True)

    id: int


class DepartmentInput(BaseModel):
    hospital_id: int
    name: str = Field(min_length=1, max_length=100)
    floor: str = Field(min_length=1, max_length=30)
    location: str = Field(min_length=1, max_length=255)


class DepartmentResponse(DepartmentInput):
    model_config = ConfigDict(from_attributes=True)

    id: int


class DoctorInput(BaseModel):
    department_id: int
    name: str = Field(min_length=1, max_length=50)
    title: str = Field(min_length=1, max_length=50)
    specialty: str = Field(min_length=1)
    photo_url: str = Field(min_length=1, max_length=500)


class DoctorResponse(DoctorInput):
    model_config = ConfigDict(from_attributes=True)

    id: int

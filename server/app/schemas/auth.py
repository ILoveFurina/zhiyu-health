from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.models.staff import StaffRole


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=50)
    password: str = Field(min_length=1, max_length=128)


class TokenResponse(BaseModel):
    access_token: str
    token_type: Literal["bearer"] = "bearer"


class StaffProfile(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    username: str
    role: StaffRole
    doctor_id: int | None

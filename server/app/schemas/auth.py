from pydantic import BaseModel, Field


class MockLoginRequest(BaseModel):
    nickname: str = Field(default="演示患者", min_length=1, max_length=50)


class PatientInfo(BaseModel):
    id: int
    nickname: str


class TokenResponse(BaseModel):
    token: str
    patient: PatientInfo

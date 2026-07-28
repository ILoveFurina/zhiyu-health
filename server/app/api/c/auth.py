from fastapi import APIRouter, Request

from app.schemas.c_auth import MockLoginRequest, MockLoginResponse, PatientInfo

router = APIRouter(prefix="/c/auth", tags=["c端"])


@router.post("/mock-login", response_model=MockLoginResponse)
def mock_login(body: MockLoginRequest, request: Request) -> MockLoginResponse:
    patient = request.app.state.patient_service.mock_login(body.nickname)
    token = request.app.state.patient_token_service.issue(patient.id)
    return MockLoginResponse(
        token=token, patient=PatientInfo(id=patient.id, nickname=patient.nickname)
    )

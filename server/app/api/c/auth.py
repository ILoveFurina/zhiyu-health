from fastapi import APIRouter, Request

from app.schemas.auth import MockLoginRequest, PatientInfo, TokenResponse

router = APIRouter(prefix="/c/auth", tags=["c端"])


@router.post("/mock-login", response_model=TokenResponse)
def mock_login(body: MockLoginRequest, request: Request) -> TokenResponse:
    patient = request.app.state.patient_service.mock_login(body.nickname)
    token = request.app.state.token_service.issue(patient.id)
    return TokenResponse(token=token, patient=PatientInfo(id=patient.id, nickname=patient.nickname))

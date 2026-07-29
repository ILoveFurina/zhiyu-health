"""处方解读与就诊小结的内部 HTTP 契约。"""

from pydantic import BaseModel, ConfigDict, Field

from app.core.contracts import get_contracts


class PrescriptionFact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=100)
    specification: str = Field(min_length=1, max_length=100)
    dosage: str = Field(min_length=1, max_length=100)
    frequency: str = Field(min_length=1, max_length=100)
    duration: str = Field(min_length=1, max_length=100)
    notes: str = Field(default="", max_length=500)


class PrescriptionExplanationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[PrescriptionFact] = Field(min_length=1, max_length=20)


class ConsultationSummaryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    diagnosis: str = Field(min_length=1, max_length=2000)
    advice: str = Field(min_length=1, max_length=2000)


class ClinicalTextResponse(BaseModel):
    content: str = Field(min_length=1)
    disclaimer: str = Field(default_factory=lambda: get_contracts().disclaimer.text)

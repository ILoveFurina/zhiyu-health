"""通用药品说明书流的内部 HTTP 契约（票 51，ADR-0028）。"""

from pydantic import BaseModel, ConfigDict, Field, field_validator


class MedicationKnowledgeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    drug_name: str = Field(min_length=1, max_length=100)

    @field_validator("drug_name")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("drug_name 不能为空")
        return value.strip()

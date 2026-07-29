from typing import Literal

from pydantic import BaseModel


class DependencyHealth(BaseModel):
    status: Literal["ok", "error"]


class KnowledgeHealth(BaseModel):
    neo4j: DependencyHealth
    pgvector: DependencyHealth


class HealthResponse(BaseModel):
    status: Literal["ok", "degraded"]
    services: KnowledgeHealth

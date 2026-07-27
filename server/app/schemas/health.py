from typing import Literal

from pydantic import BaseModel


class DependencyHealth(BaseModel):
    status: Literal["ok", "error"]


class StorageHealth(BaseModel):
    postgres: DependencyHealth
    redis: DependencyHealth
    neo4j: DependencyHealth


class HealthResponse(BaseModel):
    status: Literal["ok", "degraded"]
    services: StorageHealth

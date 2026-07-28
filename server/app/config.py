from functools import lru_cache
from pathlib import Path

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

PROJECT_ROOT = Path(__file__).resolve().parents[2]


class WebSettings(BaseSettings):
    cors_origins: str = "http://localhost:5173"

    model_config = SettingsConfigDict(
        env_file=PROJECT_ROOT / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


class Settings(WebSettings):
    database_url: str
    redis_url: str
    neo4j_uri: str
    neo4j_user: str
    neo4j_password: str
    jwt_secret: SecretStr | None = None


@lru_cache
def get_web_settings() -> WebSettings:
    return WebSettings()


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]

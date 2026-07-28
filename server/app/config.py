from functools import lru_cache
from pathlib import Path

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
    # 火山方舟（ADR-0004）；测试注入 fake Agent，不依赖这些值
    ark_api_key: str = ""
    ark_base_url: str = "https://ark.cn-beijing.volces.com/api/v3"
    doubao_chat_model: str = ""
    # C 端 mock 登录令牌
    jwt_secret: str = "dev-only-insecure-secret"
    jwt_expire_minutes: int = 720


@lru_cache
def get_web_settings() -> WebSettings:
    return WebSettings()


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]

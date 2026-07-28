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
    """Agent 层配置（ADR-0009）：只保留 LLM 与知识检索所需项。

    业务库写入、Redis 号源计数、JWT 签发/鉴权均归 server-java，此处不读。
    """

    neo4j_uri: str = "bolt://localhost:7687"
    neo4j_user: str = "neo4j"
    neo4j_password: str = ""
    # 业务工具薄壳的回调地址（server-java 统一入口）
    server_java_base_url: str = "http://localhost:8080"
    agent_callback_secret: str = "zhiyu-dev-only-agent-callback-secret"
    # 火山方舟（ADR-0004）；测试注入 fake Agent，不依赖这些值
    ark_api_key: str = ""
    ark_base_url: str = "https://ark.cn-beijing.volces.com/api/v3"
    doubao_chat_model: str = ""


@lru_cache
def get_web_settings() -> WebSettings:
    return WebSettings()


@lru_cache
def get_settings() -> Settings:
    return Settings()

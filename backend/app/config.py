from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "FaceHealth Agent Backend"
    app_env: str = "development"
    host: str = "0.0.0.0"
    port: int = 8100
    log_level: str = "INFO"

    llm_api_key: str = ""
    llm_base_url: str = "https://api.openai.com/v1"
    llm_model: str = "gpt-4.1-mini"
    llm_timeout_seconds: float = 45.0
    llm_temperature: float = 0.3
    auth_required: bool = False

    database_path: str = "./data/conversations.db"
    max_history_messages: int = 12

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_api_key.strip())

    @property
    def database_file(self) -> Path:
        return Path(self.database_path).resolve()


@lru_cache
def get_settings() -> Settings:
    return Settings()

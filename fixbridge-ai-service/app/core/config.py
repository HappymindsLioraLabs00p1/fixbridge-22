"""Configuration. Everything is an environment variable — no secrets in source."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    environment: str = "development"

    # --- Service-to-service auth -------------------------------------------------------------
    # Java presents this as a bearer token. The service is not public; only Java calls it.
    service_auth_token: str = ""

    # --- AI provider -------------------------------------------------------------------------
    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    # Must support vision AND structured outputs. Configurable so the model can change without a
    # rebuild, matching how the Java service treats model names.
    vision_model: str = "gpt-4o-mini"
    ai_timeout_seconds: float = 45.0
    # With no key the service still runs and returns deterministic stub assessments, so the whole
    # pipeline is testable and demoable without spending money.
    stub_mode: bool = True

    # --- Image limits ------------------------------------------------------------------------
    # A modern phone photo is 3-12 MB. Downscaling before the model call is the single biggest
    # cost lever: image tokens scale with pixel count, not file size.
    max_upload_bytes: int = 20 * 1024 * 1024
    max_dimension: int = 1600
    thumbnail_dimension: int = 320
    jpeg_quality: int = 82
    max_images_per_assessment: int = 4
    download_timeout_seconds: float = 20.0

    # --- Knowledge base (RAG) ------------------------------------------------------------------
    # When set, retrieval uses PostgreSQL + pgvector; otherwise an in-memory store. The pipeline is
    # identical either way, so this is a deployment choice rather than a code path.
    knowledge_database_url: str = ""
    rag_results: int = 4

    # --- Rate limiting -----------------------------------------------------------------------
    rate_limit_per_minute: int = 120

    # --- Observability -----------------------------------------------------------------------
    sentry_dsn: str = ""
    log_level: str = "INFO"


@lru_cache
def get_settings() -> Settings:
    return Settings()

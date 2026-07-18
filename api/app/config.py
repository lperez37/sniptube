from pathlib import Path

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    data_dir: Path = Path("/data")
    redis_url: str = "redis://localhost:6379"

    # Comma-separated list of allowed CORS origins. Empty = CORS disabled
    # (the bundled UI is same-origin and needs none).
    cors_origins: str = ""

    # Download defaults
    download_max_height: int = 1080

    # Clip defaults
    clip_default_mode: str = "copy"  # "copy" or "precise"
    clip_max_duration_warn: float = 120.0

    # GIF defaults
    gif_default_quality: str = "high"  # "high" or "fast"
    gif_default_width: int = 480
    gif_default_fps: int = 10
    gif_max_duration_warn: float = 15.0

    # Trim / auto-prune
    trim_after_days: int = 14

    # Worker
    worker_concurrency: int = 2

    model_config = {"env_prefix": ""}


settings = Settings()

# Ensure data directories exist
videos_dir = settings.data_dir / "videos"
videos_dir.mkdir(parents=True, exist_ok=True)

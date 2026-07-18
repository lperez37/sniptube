import logging

from arq import cron
from arq.connections import RedisSettings

from app.config import settings
from app.tasks.audio import extract_audio_task
from app.tasks.clip import create_clip
from app.tasks.download import download_video
from app.tasks.gif import create_gif
from app.tasks.redownload import redownload_video
from app.tasks.trim import trim_old_videos

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")


def parse_redis_url(url: str) -> RedisSettings:
    """Parse a redis:// URL into arq RedisSettings."""
    from urllib.parse import urlparse
    parsed = urlparse(url)
    return RedisSettings(
        host=parsed.hostname or "localhost",
        port=parsed.port or 6379,
        database=int(parsed.path.lstrip("/") or 0),
        password=parsed.password,
    )


class WorkerSettings:
    functions = [download_video, create_clip, create_gif, extract_audio_task, redownload_video]
    cron_jobs = [cron(trim_old_videos, hour=3, minute=0)]
    redis_settings = parse_redis_url(settings.redis_url)
    max_jobs = settings.worker_concurrency
    job_timeout = 600  # 10 minutes max per job

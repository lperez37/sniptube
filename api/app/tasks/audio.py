import asyncio
import logging
from pathlib import Path

from app.config import settings
from app.database import get_video, update_job
from app.utils.ffmpeg import extract_audio
from app.utils.ids import make_params_hash

logger = logging.getLogger(__name__)


def _find_source(video_id: str) -> Path:
    video_dir = settings.data_dir / "videos" / video_id
    for ext in ("mp4", "mkv", "webm"):
        f = video_dir / f"source.{ext}"
        if f.exists():
            return f
    raise FileNotFoundError(f"No source file for video {video_id}")


async def extract_audio_task(ctx: dict, job_id: str, video_id: str,
                             start_sec: float | None = None,
                             end_sec: float | None = None) -> None:
    """Extract audio (MP3) from a downloaded source."""
    await update_job(job_id, status="running", progress=10)

    video = await get_video(video_id)
    if not video or video["status"] != "ready":
        await update_job(job_id, status="failed", error="Video not ready")
        return

    try:
        source = _find_source(video_id)
    except FileNotFoundError as e:
        await update_job(job_id, status="failed", error=str(e))
        return

    # Deterministic output path for caching
    hash_params = {}
    if start_sec is not None and end_sec is not None:
        hash_params["start"] = start_sec
        hash_params["end"] = end_sec
    else:
        hash_params["full"] = True
    params_hash = make_params_hash(**hash_params)
    derivatives_dir = settings.data_dir / "videos" / video_id / "derivatives" / "audio"
    derivatives_dir.mkdir(parents=True, exist_ok=True)
    output = derivatives_dir / f"{params_hash}.mp3"

    # Serve cached if exists
    if output.exists():
        result_path = f"videos/{video_id}/derivatives/audio/{params_hash}.mp3"
        await update_job(job_id, status="completed", progress=100, result_path=result_path)
        logger.info("Audio cache hit: %s", result_path)
        return

    # Render to a temp file and atomically move into place, so a killed or
    # concurrent duplicate job can never leave a truncated file at the cache path.
    tmp_output = derivatives_dir / f"{params_hash}.{job_id}.tmp.mp3"
    try:
        await update_job(job_id, progress=30)
        await extract_audio(source, tmp_output, start_sec, end_sec)
        tmp_output.replace(output)

        result_path = f"videos/{video_id}/derivatives/audio/{params_hash}.mp3"
        await update_job(job_id, status="completed", progress=100, result_path=result_path)
        logger.info("Audio extracted: %s", result_path)

    except asyncio.CancelledError:
        # arq job_timeout / worker shutdown - mark failed so clients stop polling.
        logger.warning("Audio extraction cancelled/timed out for %s", video_id)
        tmp_output.unlink(missing_ok=True)
        await update_job(job_id, status="failed", error="Job cancelled or timed out")
        raise
    except Exception as e:
        logger.error("Audio extraction failed for %s: %s", video_id, e)
        tmp_output.unlink(missing_ok=True)
        await update_job(job_id, status="failed", error=str(e)[:500])

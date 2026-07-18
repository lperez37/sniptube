import asyncio
import logging
from pathlib import Path

from app.config import settings
from app.database import get_video, update_job
from app.utils.ffmpeg import make_gif_fast, make_gif_high
from app.utils.ids import make_params_hash

logger = logging.getLogger(__name__)


def _find_source(video_id: str) -> Path:
    video_dir = settings.data_dir / "videos" / video_id
    for ext in ("mp4", "mkv", "webm"):
        f = video_dir / f"source.{ext}"
        if f.exists():
            return f
    raise FileNotFoundError(f"No source file for video {video_id}")


async def create_gif(ctx: dict, job_id: str, video_id: str,
                     start_sec: float, end_sec: float,
                     width: int = 480, fps: int = 10, quality: str = "high",
                     crop_pct: int | None = None) -> None:
    """Generate a GIF from a downloaded source video."""
    await update_job(job_id, status="running", progress=10)

    video = await get_video(video_id)
    if not video or video["status"] != "ready":
        await update_job(job_id, status="failed", error="Video not ready")
        return

    if start_sec >= end_sec:
        await update_job(job_id, status="failed", error="start_sec must be less than end_sec")
        return

    try:
        source = _find_source(video_id)
    except FileNotFoundError as e:
        await update_job(job_id, status="failed", error=str(e))
        return

    # Deterministic output path for caching
    hash_params = dict(start=start_sec, end=end_sec, width=width, fps=fps, quality=quality)
    if crop_pct is not None:
        hash_params["crop"] = crop_pct
    params_hash = make_params_hash(**hash_params)
    derivatives_dir = settings.data_dir / "videos" / video_id / "derivatives" / "gif"
    derivatives_dir.mkdir(parents=True, exist_ok=True)
    output = derivatives_dir / f"{params_hash}.gif"

    # Serve cached if exists
    if output.exists():
        result_path = f"videos/{video_id}/derivatives/gif/{params_hash}.gif"
        await update_job(job_id, status="completed", progress=100, result_path=result_path)
        logger.info("GIF cache hit: %s", result_path)
        return

    try:
        await update_job(job_id, progress=30)

        if quality == "high":
            await make_gif_high(source, output, start_sec, end_sec, width=width, fps=fps, crop_pct=crop_pct)
        else:
            await make_gif_fast(source, output, start_sec, end_sec, width=width, fps=fps, crop_pct=crop_pct)

        result_path = f"videos/{video_id}/derivatives/gif/{params_hash}.gif"
        await update_job(job_id, status="completed", progress=100, result_path=result_path)
        logger.info("GIF created: %s (%d bytes)", result_path, output.stat().st_size)

    except asyncio.CancelledError:
        # arq job_timeout / worker shutdown - mark failed so clients stop polling.
        logger.warning("GIF cancelled/timed out for %s", video_id)
        output.unlink(missing_ok=True)
        await update_job(job_id, status="failed", error="Job cancelled or timed out")
        raise
    except Exception as e:
        logger.error("GIF failed for %s: %s", video_id, e)
        output.unlink(missing_ok=True)
        await update_job(job_id, status="failed", error=str(e)[:500])

import asyncio
import logging
from pathlib import Path

from app.config import settings
from app.database import get_video, update_job
from app.utils.ffmpeg import make_clip_copy, make_clip_precise
from app.utils.ids import make_params_hash

logger = logging.getLogger(__name__)


def _find_source(video_id: str) -> Path:
    video_dir = settings.data_dir / "videos" / video_id
    for ext in ("mp4", "mkv", "webm"):
        f = video_dir / f"source.{ext}"
        if f.exists():
            return f
    raise FileNotFoundError(f"No source file for video {video_id}")


async def create_clip(ctx: dict, job_id: str, video_id: str,
                      start_sec: float, end_sec: float, mode: str = "copy",
                      crop_pct: int | None = None) -> None:
    """Generate a video clip from a downloaded source."""
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

    # Force precise mode when crop is active (can't crop with stream copy)
    if crop_pct is not None:
        mode = "precise"

    # Deterministic output path for caching
    hash_params = dict(start=start_sec, end=end_sec, mode=mode)
    if crop_pct is not None:
        hash_params["crop"] = crop_pct
    params_hash = make_params_hash(**hash_params)
    derivatives_dir = settings.data_dir / "videos" / video_id / "derivatives" / "clip"
    derivatives_dir.mkdir(parents=True, exist_ok=True)
    output = derivatives_dir / f"{params_hash}.mp4"

    # Serve cached if exists
    if output.exists():
        result_path = f"videos/{video_id}/derivatives/clip/{params_hash}.mp4"
        await update_job(job_id, status="completed", progress=100, result_path=result_path)
        logger.info("Clip cache hit: %s", result_path)
        return

    # Render to a temp file and atomically move into place, so a killed or
    # concurrent duplicate job can never leave a truncated file at the cache path.
    tmp_output = derivatives_dir / f"{params_hash}.{job_id}.tmp.mp4"
    try:
        await update_job(job_id, progress=30)

        if mode == "precise":
            await make_clip_precise(source, tmp_output, start_sec, end_sec, crop_pct=crop_pct)
        else:
            await make_clip_copy(source, tmp_output, start_sec, end_sec)
        tmp_output.replace(output)

        result_path = f"videos/{video_id}/derivatives/clip/{params_hash}.mp4"
        await update_job(job_id, status="completed", progress=100, result_path=result_path)
        logger.info("Clip created: %s", result_path)

    except asyncio.CancelledError:
        # arq job_timeout / worker shutdown - mark failed so clients stop polling.
        logger.warning("Clip cancelled/timed out for %s", video_id)
        tmp_output.unlink(missing_ok=True)
        await update_job(job_id, status="failed", error="Job cancelled or timed out")
        raise
    except Exception as e:
        logger.error("Clip failed for %s: %s", video_id, e)
        tmp_output.unlink(missing_ok=True)
        await update_job(job_id, status="failed", error=str(e)[:500])

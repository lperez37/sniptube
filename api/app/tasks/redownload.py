import asyncio
import logging
from pathlib import Path

import yt_dlp

from app.config import settings
from app.database import get_video, update_job
from app.utils.ids import make_params_hash
from app.utils.youtube import youtube_ydl_opts

logger = logging.getLogger(__name__)


async def redownload_video(ctx: dict, job_id: str, video_id: str, height: int) -> None:
    """Download a YouTube video at a specific resolution via yt-dlp (native stream, no re-encode)."""
    await update_job(job_id, status="running", progress=10)

    video = await get_video(video_id)
    if not video or video["status"] != "ready":
        await update_job(job_id, status="failed", error="Video not ready")
        return

    url = video.get("url")
    if not url:
        await update_job(job_id, status="failed", error="Missing URL in video record")
        return

    params_hash = make_params_hash(height=height)
    derivatives_dir = settings.data_dir / "videos" / video_id / "derivatives" / "redownload"
    derivatives_dir.mkdir(parents=True, exist_ok=True)
    output = derivatives_dir / f"{params_hash}.mp4"

    # Cache hit
    if output.exists():
        result_path = f"videos/{video_id}/derivatives/redownload/{params_hash}.mp4"
        await update_job(job_id, status="completed", progress=100, result_path=result_path)
        logger.info("Redownload cache hit: %s", result_path)
        return

    try:
        await update_job(job_id, progress=20)

        ydl_opts = youtube_ydl_opts(
            format=f"bestvideo[height<={height}][ext=mp4]+bestaudio[ext=m4a]/best[height<={height}][ext=mp4]/best[height<={height}]",
            merge_output_format="mp4",
            outtmpl=str(output),
        )

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])

        await update_job(job_id, progress=90)

        # yt-dlp may have chosen a slightly different name; find the output
        if not output.exists():
            # Check if yt-dlp added an extension
            for candidate in derivatives_dir.glob(f"{params_hash}.*"):
                if candidate.suffix in (".mp4", ".mkv", ".webm"):
                    output = candidate
                    break

        if not output.exists():
            await update_job(job_id, status="failed", error="Download produced no output file")
            return

        result_path = f"videos/{video_id}/derivatives/redownload/{output.name}"
        await update_job(job_id, status="completed", progress=100, result_path=result_path)
        logger.info("Redownload complete: %s (%dp)", result_path, height)

    except asyncio.CancelledError:
        # arq job_timeout / worker shutdown - mark failed so clients stop polling.
        logger.warning("Redownload cancelled/timed out for %s at %dp", video_id, height)
        output.unlink(missing_ok=True)
        await update_job(job_id, status="failed", error="Job cancelled or timed out")
        raise
    except Exception as e:
        logger.error("Redownload failed for %s at %dp: %s", video_id, height, e)
        output.unlink(missing_ok=True)
        await update_job(job_id, status="failed", error=str(e)[:500])

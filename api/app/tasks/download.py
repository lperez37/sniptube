import asyncio
import json
import logging
from pathlib import Path

import yt_dlp

from app.config import settings
from app.database import update_job, update_video
from app.utils.subtitles import fetch_subtitles
from app.utils.youtube import youtube_ydl_opts

logger = logging.getLogger(__name__)


def _find_source_file(video_dir: Path) -> Path | None:
    for ext in ("mp4", "mkv", "webm"):
        f = video_dir / f"source.{ext}"
        if f.exists():
            return f
    return None


async def download_video(ctx: dict, job_id: str, video_id: str, url: str) -> None:
    """Download YouTube video, then attempt subtitles separately (non-fatal)."""
    video_dir = settings.data_dir / "videos" / video_id
    video_dir.mkdir(parents=True, exist_ok=True)
    subs_dir = video_dir / "subs"
    subs_dir.mkdir(exist_ok=True)

    await update_job(job_id, status="running", progress=10)

    # Idempotent check - hydrate DB fields from meta.json so a re-download
    # after a DB reset restores title/duration instead of leaving them NULL.
    existing = _find_source_file(video_dir)
    meta_path = video_dir / "meta.json"
    if existing and meta_path.exists():
        logger.info("Video %s already downloaded, skipping", video_id)
        fields: dict = {"status": "ready"}
        try:
            meta = json.loads(meta_path.read_text())
            fields.update(
                title=meta.get("title"),
                duration=meta.get("duration"),
                language=meta.get("language"),
                thumbnail_url=meta.get("thumbnail"),
            )
        except (json.JSONDecodeError, OSError):
            pass
        await update_job(job_id, status="completed", progress=100)
        await update_video(video_id, **fields)
        return

    try:
        # Step 1: Download video (no subtitles)
        ydl_opts = youtube_ydl_opts(
            format=f"bestvideo[height<={settings.download_max_height}][ext=mp4]+bestaudio[ext=m4a]/best[height<={settings.download_max_height}][ext=mp4]/best",
            merge_output_format="mp4",
            outtmpl=str(video_dir / "source.%(ext)s"),
        )

        await update_job(job_id, progress=20)

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)

        await update_job(job_id, progress=70)

        # Extract available video heights from formats
        available_heights = sorted(
            {
                f["height"]
                for f in (info.get("formats") or [])
                if f.get("height") and f.get("vcodec") and f["vcodec"] != "none"
            },
            reverse=True,
        )

        # Save metadata
        meta = {
            "youtube_id": info.get("id"),
            "title": info.get("title"),
            "duration": info.get("duration"),
            "language": info.get("language"),
            "uploader": info.get("uploader"),
            "upload_date": info.get("upload_date"),
            "description": info.get("description", "")[:500],
            "thumbnail": info.get("thumbnail"),
            "available_heights": available_heights,
            "source_height": info.get("height"),
        }
        (video_dir / "meta.json").write_text(json.dumps(meta, indent=2))

        await update_job(job_id, progress=80)

        # Step 2: Fetch subtitles via youtube-transcript-api (non-fatal)
        subtitle_langs = []
        try:
            youtube_id = info.get("id") or meta.get("youtube_id")
            original_lang = info.get("language") or "en"
            wanted_langs = [original_lang]
            if original_lang != "en":
                wanted_langs.append("en")

            subtitle_langs = fetch_subtitles(youtube_id, subs_dir, wanted_langs)
            logger.info("Subtitles fetched for %s: %s", video_id, subtitle_langs)
        except Exception as sub_err:
            logger.warning("Subtitle fetch failed for %s (non-fatal): %s", video_id, sub_err)

        # Update video record
        await update_video(
            video_id,
            title=meta["title"],
            duration=meta["duration"],
            language=meta.get("language"),
            thumbnail_url=meta.get("thumbnail"),
            subtitles=subtitle_langs,
            status="ready",
        )

        await update_job(job_id, status="completed", progress=100)
        logger.info("Download complete: %s (%s)", video_id, meta.get("title"))

    except asyncio.CancelledError:
        # arq job_timeout / worker shutdown raises CancelledError, which plain
        # `except Exception` misses - reconcile the DB so clients stop polling.
        logger.warning("Download cancelled/timed out for %s", video_id)
        await update_job(job_id, status="failed", error="Job cancelled or timed out")
        await update_video(video_id, status="failed")
        raise
    except Exception as e:
        logger.error("Download failed for %s: %s", video_id, e)
        await update_job(job_id, status="failed", error=str(e)[:500])
        await update_video(video_id, status="failed")

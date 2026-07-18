import asyncio
import logging
import shutil

from app.config import settings
from app.database import delete_video, list_expired_unprotected

logger = logging.getLogger(__name__)


async def trim_old_videos(ctx):
    """Remove unprotected videos older than trim_after_days."""
    max_age = settings.trim_after_days
    expired = await list_expired_unprotected(max_age)
    deleted = 0
    for video in expired:
        video_id = video["id"]
        video_dir = settings.data_dir / "videos" / video_id
        if video_dir.exists():
            await asyncio.to_thread(shutil.rmtree, video_dir)
        await delete_video(video_id)
        deleted += 1
        logger.info("Trimmed video %s (%s)", video_id, video.get("title", "untitled"))
    logger.info("Trim complete: deleted %d videos (max_age=%d days)", deleted, max_age)
    return deleted

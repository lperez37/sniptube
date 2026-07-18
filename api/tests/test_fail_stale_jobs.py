"""Tests for app.database.fail_stale_jobs (startup orphan sweep)."""

from datetime import datetime, timedelta, timezone

from app import database as db

VID = "abc123def456"


async def _make_video(video_id=VID, youtube_id="dQw4w9WgXcQ", url="https://youtu.be/dQw4w9WgXcQ"):
    return await db.create_video(video_id, youtube_id, url)


async def _set_job_times(job_id: str, *, created_at: datetime | None = None,
                         updated_at: datetime | None = None) -> None:
    """Backdate job timestamps directly - the update helpers always stamp now()."""
    conn = await db.get_db()
    try:
        if created_at is not None:
            await conn.execute(
                "UPDATE jobs SET created_at = ? WHERE id = ?", (created_at.isoformat(), job_id)
            )
        if updated_at is not None:
            await conn.execute(
                "UPDATE jobs SET updated_at = ? WHERE id = ?", (updated_at.isoformat(), job_id)
            )
        await conn.commit()
    finally:
        await conn.close()


def _ago(**kwargs) -> datetime:
    return datetime.now(timezone.utc) - timedelta(**kwargs)


# --- stale running jobs ---

async def test_stale_running_job_failed_fresh_running_untouched():
    await _make_video()
    await db.update_video(VID, status="ready")  # keep the video sweep out of this test

    await db.create_job("j-stale", VID, "clip", {})
    await db.update_job("j-stale", status="running")
    await _set_job_times("j-stale", updated_at=_ago(minutes=60))

    await db.create_job("j-fresh", VID, "clip", {})
    await db.update_job("j-fresh", status="running")  # updated_at = now

    swept = await db.fail_stale_jobs()
    assert swept == 1

    stale = await db.get_job("j-stale")
    assert stale["status"] == "failed"
    assert stale["error"] == "Orphaned by a restart"

    fresh = await db.get_job("j-fresh")
    assert fresh["status"] == "running"
    assert fresh["error"] is None


async def test_running_threshold_is_configurable():
    await _make_video()
    await db.update_video(VID, status="ready")
    await db.create_job("j-run", VID, "clip", {})
    await db.update_job("j-run", status="running")
    await _set_job_times("j-run", updated_at=_ago(minutes=10))

    assert await db.fail_stale_jobs() == 0  # default 45 min: untouched
    assert (await db.get_job("j-run"))["status"] == "running"

    assert await db.fail_stale_jobs(running_max_minutes=5) == 1
    assert (await db.get_job("j-run"))["status"] == "failed"


# --- stale queued jobs ---

async def test_old_queued_job_failed_recent_queued_untouched():
    await _make_video()
    await db.update_video(VID, status="ready")

    await db.create_job("j-old-q", VID, "clip", {})
    await _set_job_times("j-old-q", created_at=_ago(hours=25))

    await db.create_job("j-new-q", VID, "clip", {})  # created_at = now

    swept = await db.fail_stale_jobs()
    assert swept == 1

    old = await db.get_job("j-old-q")
    assert old["status"] == "failed"
    assert old["error"] == "Orphaned by a restart"

    new = await db.get_job("j-new-q")
    assert new["status"] == "queued"
    assert new["error"] is None


# --- downloading videos without a live download job ---

async def test_downloading_video_without_active_download_job_flips_failed():
    await _make_video("vid-orphaned", "AAAAAAAAAAA", "u1")  # default status 'downloading'

    await _make_video("vid-live", "BBBBBBBBBBB", "u2")
    await db.create_job("j-dl", "vid-live", "download", {})  # fresh queued download

    await db.fail_stale_jobs()

    assert (await db.get_video("vid-orphaned"))["status"] == "failed"
    assert (await db.get_video("vid-live"))["status"] == "downloading"


async def test_download_job_swept_also_fails_its_video():
    # The video flip runs after the job sweep, so a download job orphaned by
    # the sweep no longer counts as live and its video flips too.
    await _make_video()
    await db.create_job("j-dl", VID, "download", {})
    await db.update_job("j-dl", status="running")
    await _set_job_times("j-dl", updated_at=_ago(minutes=60))

    swept = await db.fail_stale_jobs()
    assert swept == 1
    assert (await db.get_job("j-dl"))["status"] == "failed"
    assert (await db.get_video(VID))["status"] == "failed"


# --- return value counts every swept job ---

async def test_returns_count_of_swept_jobs():
    await _make_video()
    await db.update_video(VID, status="ready")

    for i in range(2):
        await db.create_job(f"j-run-{i}", VID, "clip", {})
        await db.update_job(f"j-run-{i}", status="running")
        await _set_job_times(f"j-run-{i}", updated_at=_ago(minutes=90))
    await db.create_job("j-old-q", VID, "gif", {})
    await _set_job_times("j-old-q", created_at=_ago(hours=30))
    await db.create_job("j-done", VID, "clip", {})
    await db.update_job("j-done", status="completed")
    await _set_job_times("j-done", updated_at=_ago(minutes=90))  # completed: never swept

    assert await db.fail_stale_jobs() == 3
    assert (await db.get_job("j-done"))["status"] == "completed"
    assert await db.fail_stale_jobs() == 0  # idempotent second sweep

"""Tests for app/database.py."""

from datetime import datetime, timedelta, timezone

import pytest

from app import database as db

VID = "abc123def456"


async def _make_video(video_id=VID, youtube_id="dQw4w9WgXcQ", url="https://youtu.be/dQw4w9WgXcQ"):
    return await db.create_video(video_id, youtube_id, url)


async def _set_created_at(video_id: str, dt: datetime) -> None:
    conn = await db.get_db()
    try:
        await conn.execute("UPDATE videos SET created_at = ? WHERE id = ?", (dt.isoformat(), video_id))
        await conn.commit()
    finally:
        await conn.close()


# --- init_db ---

async def test_init_db_idempotent():
    await db.init_db()  # already ran once in the fixture; run again
    await db.init_db()
    video = await _make_video()
    assert video["protected"] == 0  # migration column present with default


# --- video CRUD ---

async def test_video_crud_roundtrip():
    created = await _make_video()
    assert created["id"] == VID
    assert created["status"] == "downloading"
    assert created["subtitles"] == []

    fetched = await db.get_video(VID)
    assert fetched == created

    updated = await db.update_video(VID, title="A Title", duration=12.5, subtitles=["en", "es"], status="ready")
    assert updated["title"] == "A Title"
    assert updated["duration"] == 12.5
    assert updated["subtitles"] == ["en", "es"]
    assert updated["status"] == "ready"

    all_videos = await db.list_videos()
    assert [v["id"] for v in all_videos] == [VID]

    assert await db.delete_video(VID) is True
    assert await db.get_video(VID) is None
    assert await db.delete_video(VID) is False


async def test_create_video_is_insert_or_ignore():
    first = await _make_video()
    await db.update_video(VID, title="kept")
    second = await _make_video()
    assert second["title"] == "kept"
    assert second["created_at"] == first["created_at"]


async def test_get_video_missing_returns_none():
    assert await db.get_video("nope") is None


async def test_update_video_rejects_unknown_columns():
    await _make_video()
    with pytest.raises(ValueError, match="Unknown video columns"):
        await db.update_video(VID, title="ok", bogus_col="x")


async def test_update_video_no_kwargs_returns_unchanged():
    created = await _make_video()
    result = await db.update_video(VID)
    assert result == created


# --- job CRUD ---

async def test_job_crud_roundtrip():
    await _make_video()
    job = await db.create_job("job-1", VID, "download", {"url": "u"})
    assert job["status"] == "queued"
    assert job["progress"] == 0
    assert job["params"] == {"url": "u"}

    fetched = await db.get_job("job-1")
    assert fetched == job

    updated = await db.update_job("job-1", status="running", progress=50)
    assert updated["status"] == "running"
    assert updated["progress"] == 50
    assert updated["updated_at"] >= job["updated_at"]

    updated = await db.update_job("job-1", params={"url": "u", "extra": 1})
    assert updated["params"] == {"url": "u", "extra": 1}

    assert await db.delete_job("job-1") is True
    assert await db.get_job("job-1") is None


async def test_update_job_rejects_unknown_columns():
    await _make_video()
    await db.create_job("job-1", VID, "download", {})
    with pytest.raises(ValueError, match="Unknown job columns"):
        await db.update_job("job-1", status="running", nope=1)


async def test_get_job_missing_returns_none():
    assert await db.get_job("missing") is None


# --- get_active_job ---

async def test_get_active_job_only_queued_or_running():
    await _make_video()
    await db.create_job("j-queued", VID, "download", {})
    assert (await db.get_active_job(VID, "download"))["id"] == "j-queued"

    await db.update_job("j-queued", status="running")
    assert (await db.get_active_job(VID, "download"))["id"] == "j-queued"

    await db.update_job("j-queued", status="completed")
    assert await db.get_active_job(VID, "download") is None

    await db.create_job("j-failed", VID, "download", {})
    await db.update_job("j-failed", status="failed")
    assert await db.get_active_job(VID, "download") is None


async def test_get_active_job_filters_by_type():
    await _make_video()
    await db.create_job("j-clip", VID, "clip", {})
    assert await db.get_active_job(VID, "download") is None
    assert (await db.get_active_job(VID, "clip"))["id"] == "j-clip"


async def test_get_active_job_other_video_not_returned():
    await _make_video()
    await db.create_job("j1", VID, "download", {})
    assert await db.get_active_job("other-video", "download") is None


# --- list_expired_unprotected ---

async def test_list_expired_unprotected():
    now = datetime.now(timezone.utc)
    old = now - timedelta(days=30)

    # Old, ready, unprotected -> included
    await _make_video("old-ready-unpr", "AAAAAAAAAAA", "u1")
    await db.update_video("old-ready-unpr", status="ready")
    await _set_created_at("old-ready-unpr", old)

    # Old, ready, protected -> excluded
    await _make_video("old-ready-prot", "BBBBBBBBBBB", "u2")
    await db.update_video("old-ready-prot", status="ready")
    await db.set_video_protected("old-ready-prot", True)
    await _set_created_at("old-ready-prot", old)

    # Recent, ready, unprotected -> excluded (within cutoff)
    await _make_video("new-ready-unpr", "CCCCCCCCCCC", "u3")
    await db.update_video("new-ready-unpr", status="ready")

    # Old, unprotected but still downloading -> excluded (not ready)
    await _make_video("old-downloading", "DDDDDDDDDDD", "u4")
    await _set_created_at("old-downloading", old)

    expired = await db.list_expired_unprotected(14)
    assert [v["id"] for v in expired] == ["old-ready-unpr"]


async def test_list_expired_respects_cutoff_boundary():
    await _make_video()
    await db.update_video(VID, status="ready")
    await _set_created_at(VID, datetime.now(timezone.utc) - timedelta(days=5))
    assert await db.list_expired_unprotected(14) == []
    assert [v["id"] for v in await db.list_expired_unprotected(4)] == [VID]


async def test_set_video_protected_roundtrip():
    await _make_video()
    v = await db.set_video_protected(VID, True)
    assert v["protected"] == 1
    v = await db.set_video_protected(VID, False)
    assert v["protected"] == 0

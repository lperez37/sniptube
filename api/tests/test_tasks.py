"""Cancellation handling in app/tasks/clip.py and app/tasks/download.py."""

import asyncio

import pytest

from app import database as db
from app.tasks import clip as clip_mod
from app.tasks import download as download_mod

VID = "abc123def456"
URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"


async def _ready_video_with_source(data_dir):
    await db.create_video(VID, "dQw4w9WgXcQ", URL)
    await db.update_video(VID, status="ready")
    video_dir = data_dir / "videos" / VID
    video_dir.mkdir(parents=True, exist_ok=True)
    (video_dir / "source.mp4").write_bytes(b"fake mp4")


async def test_create_clip_cancellation_marks_job_failed(data_dir, monkeypatch):
    await _ready_video_with_source(data_dir)
    job = await db.create_job("clip-job", VID, "clip", {})

    async def cancelled_copy(source, output, start, end):
        raise asyncio.CancelledError()

    monkeypatch.setattr(clip_mod, "make_clip_copy", cancelled_copy)

    with pytest.raises(asyncio.CancelledError):
        await clip_mod.create_clip({}, "clip-job", VID, 0.0, 5.0, mode="copy")

    job = await db.get_job("clip-job")
    assert job["status"] == "failed"
    assert "cancelled" in job["error"].lower()


async def test_create_clip_generic_error_does_not_propagate(data_dir, monkeypatch):
    await _ready_video_with_source(data_dir)
    await db.create_job("clip-job2", VID, "clip", {})

    async def broken_copy(source, output, start, end):
        raise RuntimeError("ffmpeg exploded")

    monkeypatch.setattr(clip_mod, "make_clip_copy", broken_copy)

    await clip_mod.create_clip({}, "clip-job2", VID, 0.0, 5.0, mode="copy")  # no raise
    job = await db.get_job("clip-job2")
    assert job["status"] == "failed"
    assert "ffmpeg exploded" in job["error"]


async def test_create_clip_rejects_invalid_range(data_dir):
    await _ready_video_with_source(data_dir)
    await db.create_job("clip-job3", VID, "clip", {})
    await clip_mod.create_clip({}, "clip-job3", VID, 10.0, 5.0)
    job = await db.get_job("clip-job3")
    assert job["status"] == "failed"
    assert "start_sec" in job["error"]


async def test_download_cancellation_marks_job_and_video_failed(data_dir, monkeypatch):
    await db.create_video(VID, "dQw4w9WgXcQ", URL)
    await db.create_job("dl-job", VID, "download", {"url": URL})

    class CancelledYDL:
        def __init__(self, *args, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def extract_info(self, url, download=False):
            raise asyncio.CancelledError()

    monkeypatch.setattr(download_mod.yt_dlp, "YoutubeDL", CancelledYDL)

    with pytest.raises(asyncio.CancelledError):
        await download_mod.download_video({}, "dl-job", VID, URL)

    job = await db.get_job("dl-job")
    assert job["status"] == "failed"
    assert "cancelled" in job["error"].lower()
    video = await db.get_video(VID)
    assert video["status"] == "failed"

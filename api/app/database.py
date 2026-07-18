import json
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

import aiosqlite

from app.config import settings

DB_PATH = settings.data_dir / "video-clips.db"

# Columns that may be set via the **kwargs update helpers. Guards against
# SQL injection through column names and catches typos loudly.
VIDEO_UPDATE_COLUMNS = frozenset(
    {"title", "duration", "language", "thumbnail_url", "subtitles", "status", "protected"}
)
JOB_UPDATE_COLUMNS = frozenset({"status", "progress", "result_path", "error", "params"})


async def get_db() -> aiosqlite.Connection:
    db = await aiosqlite.connect(str(DB_PATH))
    db.row_factory = aiosqlite.Row
    await db.execute("PRAGMA journal_mode=WAL")
    await db.execute("PRAGMA foreign_keys=ON")
    return db


async def init_db() -> None:
    db = await get_db()
    try:
        await db.executescript("""
            CREATE TABLE IF NOT EXISTS videos (
                id TEXT PRIMARY KEY,
                youtube_id TEXT NOT NULL,
                url TEXT NOT NULL,
                title TEXT,
                duration REAL,
                language TEXT,
                thumbnail_url TEXT,
                subtitles TEXT DEFAULT '[]',
                created_at TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'downloading'
            );

            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY,
                video_id TEXT NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
                type TEXT NOT NULL,
                params TEXT NOT NULL DEFAULT '{}',
                status TEXT NOT NULL DEFAULT 'queued',
                progress INTEGER NOT NULL DEFAULT 0,
                result_path TEXT,
                error TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_jobs_video_id ON jobs(video_id);
            CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
        """)
        await db.commit()

        # Idempotent migration: add protected column
        try:
            await db.execute("ALTER TABLE videos ADD COLUMN protected INTEGER NOT NULL DEFAULT 0")
            await db.commit()
        except sqlite3.OperationalError as e:
            if "duplicate column" not in str(e).lower():
                raise
    finally:
        await db.close()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


# --- Video CRUD ---

async def create_video(video_id: str, youtube_id: str, url: str) -> dict:
    db = await get_db()
    try:
        await db.execute(
            "INSERT OR IGNORE INTO videos (id, youtube_id, url, created_at) VALUES (?, ?, ?, ?)",
            (video_id, youtube_id, url, _now()),
        )
        await db.commit()
        return await get_video(video_id)
    finally:
        await db.close()


async def get_video(video_id: str) -> dict | None:
    db = await get_db()
    try:
        cursor = await db.execute("SELECT * FROM videos WHERE id = ?", (video_id,))
        row = await cursor.fetchone()
        if row is None:
            return None
        d = dict(row)
        d["subtitles"] = json.loads(d["subtitles"]) if d["subtitles"] else []
        return d
    finally:
        await db.close()


async def list_videos() -> list[dict]:
    db = await get_db()
    try:
        cursor = await db.execute("SELECT * FROM videos ORDER BY created_at DESC")
        rows = await cursor.fetchall()
        results = []
        for row in rows:
            d = dict(row)
            d["subtitles"] = json.loads(d["subtitles"]) if d["subtitles"] else []
            results.append(d)
        return results
    finally:
        await db.close()


async def update_video(video_id: str, **kwargs) -> dict | None:
    if not kwargs:
        return await get_video(video_id)
    unknown = set(kwargs) - VIDEO_UPDATE_COLUMNS
    if unknown:
        raise ValueError(f"Unknown video columns: {sorted(unknown)}")
    db = await get_db()
    try:
        sets = []
        vals = []
        for k, v in kwargs.items():
            if k == "subtitles":
                v = json.dumps(v)
            sets.append(f"{k} = ?")
            vals.append(v)
        vals.append(video_id)
        await db.execute(f"UPDATE videos SET {', '.join(sets)} WHERE id = ?", vals)
        await db.commit()
        return await get_video(video_id)
    finally:
        await db.close()


async def delete_video(video_id: str) -> bool:
    db = await get_db()
    try:
        cursor = await db.execute("DELETE FROM videos WHERE id = ?", (video_id,))
        await db.commit()
        return cursor.rowcount > 0
    finally:
        await db.close()


# --- Job CRUD ---

async def create_job(job_id: str, video_id: str, job_type: str, params: dict) -> dict:
    now = _now()
    db = await get_db()
    try:
        await db.execute(
            "INSERT INTO jobs (id, video_id, type, params, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            (job_id, video_id, job_type, json.dumps(params), now, now),
        )
        await db.commit()
        return await get_job(job_id)
    finally:
        await db.close()


async def get_job(job_id: str) -> dict | None:
    db = await get_db()
    try:
        cursor = await db.execute("SELECT * FROM jobs WHERE id = ?", (job_id,))
        row = await cursor.fetchone()
        if row is None:
            return None
        d = dict(row)
        d["params"] = json.loads(d["params"]) if d["params"] else {}
        return d
    finally:
        await db.close()


async def update_job(job_id: str, **kwargs) -> dict | None:
    unknown = set(kwargs) - JOB_UPDATE_COLUMNS
    if unknown:
        raise ValueError(f"Unknown job columns: {sorted(unknown)}")
    db = await get_db()
    try:
        sets = ["updated_at = ?"]
        vals = [_now()]
        for k, v in kwargs.items():
            if k == "params":
                v = json.dumps(v)
            sets.append(f"{k} = ?")
            vals.append(v)
        vals.append(job_id)
        await db.execute(f"UPDATE jobs SET {', '.join(sets)} WHERE id = ?", vals)
        await db.commit()
        return await get_job(job_id)
    finally:
        await db.close()


async def fail_stale_jobs(running_max_minutes: int = 30, queued_max_hours: int = 24) -> int:
    """Mark orphaned jobs as failed.

    A 'running' job not updated within running_max_minutes is dead (the worker's
    job_timeout would have fired long before). A 'queued' job older than
    queued_max_hours was lost across restarts - the threshold is generous so a
    long legitimate bulk-download queue is never false-failed.
    Returns the number of jobs swept. Called on API and worker startup.
    """
    now = datetime.now(timezone.utc)
    running_cutoff = (now - timedelta(minutes=running_max_minutes)).isoformat()
    queued_cutoff = (now - timedelta(hours=queued_max_hours)).isoformat()
    db = await get_db()
    try:
        cursor = await db.execute(
            """
            UPDATE jobs SET status = 'failed', error = 'Orphaned by a restart', updated_at = ?
            WHERE (status = 'running' AND updated_at < ?)
               OR (status = 'queued' AND created_at < ?)
            """,
            (now.isoformat(), running_cutoff, queued_cutoff),
        )
        await db.commit()
        swept = cursor.rowcount
        # Videos stuck in 'downloading' with no live download job flip to failed
        # so the UI stops showing them as in-progress.
        await db.execute(
            """
            UPDATE videos SET status = 'failed'
            WHERE status = 'downloading' AND id NOT IN (
                SELECT video_id FROM jobs WHERE type = 'download' AND status IN ('queued','running')
            )
            """
        )
        await db.commit()
        return swept
    finally:
        await db.close()


async def list_active_jobs(job_type: str | None = None) -> list[dict]:
    """All queued/running jobs, optionally filtered by type, newest first."""
    db = await get_db()
    try:
        if job_type:
            cursor = await db.execute(
                "SELECT * FROM jobs WHERE status IN ('queued','running') AND type = ? ORDER BY created_at DESC",
                (job_type,),
            )
        else:
            cursor = await db.execute(
                "SELECT * FROM jobs WHERE status IN ('queued','running') ORDER BY created_at DESC"
            )
        rows = await cursor.fetchall()
        results = []
        for row in rows:
            d = dict(row)
            d["params"] = json.loads(d["params"]) if d["params"] else {}
            results.append(d)
        return results
    finally:
        await db.close()


async def get_active_job(video_id: str, job_type: str) -> dict | None:
    """Most recent queued/running job of a type for a video, or None."""
    db = await get_db()
    try:
        cursor = await db.execute(
            "SELECT * FROM jobs WHERE video_id = ? AND type = ? AND status IN ('queued','running') "
            "ORDER BY created_at DESC LIMIT 1",
            (video_id, job_type),
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        d = dict(row)
        d["params"] = json.loads(d["params"]) if d["params"] else {}
        return d
    finally:
        await db.close()


async def delete_job(job_id: str) -> bool:
    db = await get_db()
    try:
        cursor = await db.execute("DELETE FROM jobs WHERE id = ?", (job_id,))
        await db.commit()
        return cursor.rowcount > 0
    finally:
        await db.close()


async def get_derivative_result_paths(video_id: str) -> list[str]:
    """Return result_path values for completed derivative jobs of a video."""
    db = await get_db()
    try:
        cursor = await db.execute(
            "SELECT result_path FROM jobs WHERE video_id = ? AND type IN ('clip','gif','audio','redownload') AND status = 'completed' AND result_path IS NOT NULL",
            (video_id,),
        )
        rows = await cursor.fetchall()
        return [row["result_path"] for row in rows]
    finally:
        await db.close()


async def get_all_derivative_stats() -> dict[str, dict]:
    """Return {video_id: {count, paths}} for all videos with completed derivatives."""
    db = await get_db()
    try:
        cursor = await db.execute(
            "SELECT video_id, result_path FROM jobs WHERE type IN ('clip','gif','audio','redownload') AND status = 'completed' AND result_path IS NOT NULL"
        )
        rows = await cursor.fetchall()
        stats: dict[str, dict] = {}
        for row in rows:
            vid = row["video_id"]
            if vid not in stats:
                stats[vid] = {"count": 0, "paths": []}
            stats[vid]["count"] += 1
            stats[vid]["paths"].append(row["result_path"])
        return stats
    finally:
        await db.close()


async def set_video_protected(video_id: str, protected: bool) -> dict | None:
    db = await get_db()
    try:
        await db.execute("UPDATE videos SET protected = ? WHERE id = ?", (int(protected), video_id))
        await db.commit()
        return await get_video(video_id)
    finally:
        await db.close()


async def list_expired_unprotected(max_age_days: int) -> list[dict]:
    db = await get_db()
    try:
        cutoff = datetime.now(timezone.utc) - timedelta(days=max_age_days)
        cursor = await db.execute(
            "SELECT * FROM videos WHERE protected = 0 AND created_at < ? AND status = 'ready'",
            (cutoff.isoformat(),),
        )
        rows = await cursor.fetchall()
        results = []
        for row in rows:
            d = dict(row)
            d["subtitles"] = json.loads(d["subtitles"]) if d["subtitles"] else []
            results.append(d)
        return results
    finally:
        await db.close()


async def list_jobs_for_video(video_id: str) -> list[dict]:
    db = await get_db()
    try:
        cursor = await db.execute(
            "SELECT * FROM jobs WHERE video_id = ? ORDER BY created_at DESC",
            (video_id,),
        )
        rows = await cursor.fetchall()
        results = []
        for row in rows:
            d = dict(row)
            d["params"] = json.loads(d["params"]) if d["params"] else {}
            results.append(d)
        return results
    finally:
        await db.close()

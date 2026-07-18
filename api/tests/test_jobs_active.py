"""Tests for GET /jobs/active (app/routers/jobs.py)."""

from datetime import datetime, timedelta, timezone

from app import database as db

VID = "abc123def456"
YT_URL = "https://youtu.be/dQw4w9WgXcQ"


async def _set_job_created_at(job_id: str, dt: datetime) -> None:
    conn = await db.get_db()
    try:
        await conn.execute(
            "UPDATE jobs SET created_at = ? WHERE id = ?", (dt.isoformat(), job_id)
        )
        await conn.commit()
    finally:
        await conn.close()


async def _seed_jobs():
    await db.create_video(VID, "dQw4w9WgXcQ", YT_URL)
    now = datetime.now(timezone.utc)
    # Explicit created_at values so the newest-first ordering is deterministic.
    await db.create_job("j-queued-old", VID, "download", {})
    await _set_job_created_at("j-queued-old", now - timedelta(minutes=30))
    await db.create_job("j-running-new", VID, "clip", {})
    await db.update_job("j-running-new", status="running")
    await _set_job_created_at("j-running-new", now - timedelta(minutes=5))
    await db.create_job("j-completed", VID, "clip", {})
    await db.update_job("j-completed", status="completed")
    await db.create_job("j-failed", VID, "gif", {})
    await db.update_job("j-failed", status="failed")


async def test_active_returns_queued_and_running_newest_first(client):
    await _seed_jobs()
    r = await client.get("/jobs/active")
    assert r.status_code == 200
    body = r.json()
    assert [j["id"] for j in body] == ["j-running-new", "j-queued-old"]
    assert {j["status"] for j in body} == {"running", "queued"}


async def test_active_type_filter(client):
    await _seed_jobs()
    r = await client.get("/jobs/active", params={"type": "download"})
    assert r.status_code == 200
    body = r.json()
    assert [j["id"] for j in body] == ["j-queued-old"]
    assert body[0]["type"] == "download"

    r = await client.get("/jobs/active", params={"type": "audio"})
    assert r.status_code == 200
    assert r.json() == []


async def test_active_route_not_shadowed_by_job_id(client):
    # With no jobs at all, /jobs/active must hit the list route (200 + list),
    # not fall through to /jobs/{job_id} and 404 as job id "active".
    r = await client.get("/jobs/active")
    assert r.status_code == 200
    assert r.json() == []

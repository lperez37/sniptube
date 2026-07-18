"""Endpoint tests via httpx.AsyncClient + ASGITransport."""

import yt_dlp

from app import database as db
from app.routers import search as search_mod
from app.utils.ids import make_video_id

YT_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"


def _entries(n):
    return [
        {
            "id": f"vid{i:08d}",  # exactly 11 chars
            "title": f"Video {i}",
            "duration": 60 + i,
            "view_count": 1000 - i,
            "description": f"desc {i}",
        }
        for i in range(n)
    ]


def _patch_search(monkeypatch, entries):
    """Patch _run_search with a fake returning at most max_results entries."""
    calls = []

    def fake_run_search(query, max_results):
        calls.append((query, max_results))
        return entries[:max_results]

    monkeypatch.setattr(search_mod, "_run_search", fake_run_search)
    return calls


# --- GET /search ---

async def test_search_returns_results(client, monkeypatch):
    _patch_search(monkeypatch, _entries(20))
    r = await client.get("/search", params={"q": "cats", "max_results": 5})
    assert r.status_code == 200
    body = r.json()
    assert body["query"] == "cats"
    assert len(body["results"]) == 5
    assert body["results"][0]["youtube_id"] == "vid00000000"
    assert body["page"] == 1
    assert body["page_size"] == 5
    assert body["has_more"] is True


async def test_search_pagination_slices_and_cache_consistency(client, monkeypatch):
    # 8 total entries < fetch_count 10 -> cache marked exhausted, so page 2
    # is served from the cached list and _run_search runs only once.
    calls = _patch_search(monkeypatch, _entries(8))

    r1 = await client.get("/search", params={"q": "cats", "max_results": 5, "page": 1})
    r2 = await client.get("/search", params={"q": "cats", "max_results": 5, "page": 2})
    assert r1.status_code == 200 and r2.status_code == 200
    assert len(calls) == 1  # page 2 came from the cache

    page1_ids = [x["youtube_id"] for x in r1.json()["results"]]
    page2_ids = [x["youtube_id"] for x in r2.json()["results"]]
    assert page1_ids == [f"vid{i:08d}" for i in range(5)]
    assert page2_ids == [f"vid{i:08d}" for i in range(5, 8)]
    assert not set(page1_ids) & set(page2_ids)
    assert r1.json()["has_more"] is True  # 8 entries > page 1 end (5)
    assert r2.json()["has_more"] is False  # nothing beyond entry 8


async def test_search_channel_query_uses_channel_search(client, monkeypatch):
    def fake_channel(handle, max_results):
        assert handle == "@RickAstleyYT"
        return _entries(3), "Rick Astley"

    monkeypatch.setattr(search_mod, "_run_channel_search", fake_channel)
    r = await client.get("/search", params={"q": "@RickAstleyYT"})
    assert r.status_code == 200
    body = r.json()
    assert body["filters_applied"]["channel"] == "@RickAstleyYT"
    assert body["results"][0]["uploader"] == "Rick Astley"


async def test_search_downloaderror_returns_502(client, monkeypatch):
    def boom(query, max_results):
        raise yt_dlp.utils.DownloadError("nope")

    monkeypatch.setattr(search_mod, "_run_search", boom)
    r = await client.get("/search", params={"q": "cats"})
    assert r.status_code == 502
    assert "YouTube search failed" in r.json()["detail"]


async def test_search_rejects_invalid_sort_by(client):
    r = await client.get("/search", params={"q": "cats", "sort_by": "rating"})
    assert r.status_code == 422


async def test_search_rejects_invalid_pages(client):
    r = await client.get("/search", params={"q": "cats", "page": 0})
    assert r.status_code == 422
    r = await client.get("/search", params={"q": "cats", "page": 6})
    assert r.status_code == 422


# --- POST /videos ---

async def test_create_video_happy_path_enqueues_once(client, fake_pool):
    r = await client.post("/videos", json={"url": YT_URL})
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "queued"
    assert body["video_id"] == make_video_id(YT_URL)
    assert body["job_id"]

    assert len(fake_pool.calls) == 1
    func, job_id, video_id, url = fake_pool.calls[0]
    assert (func, job_id, video_id, url) == ("download_video", body["job_id"], body["video_id"], YT_URL)

    job = await db.get_job(body["job_id"])
    assert job["type"] == "download"
    assert job["status"] == "queued"


async def test_create_video_concurrent_download_dedupes(client, fake_pool):
    r1 = await client.post("/videos", json={"url": YT_URL})
    r2 = await client.post("/videos", json={"url": YT_URL})
    assert r1.status_code == 200 and r2.status_code == 200
    assert r2.json()["status"] == "queued"
    assert r2.json()["job_id"] == r1.json()["job_id"]  # same in-flight job
    assert len(fake_pool.calls) == 1  # NOT enqueued twice


async def test_create_video_already_exists_when_ready(client, fake_pool):
    r1 = await client.post("/videos", json={"url": YT_URL})
    await db.update_video(r1.json()["video_id"], status="ready")
    r2 = await client.post("/videos", json={"url": YT_URL})
    assert r2.json() == {"video_id": r1.json()["video_id"], "job_id": "", "status": "already_exists"}
    assert len(fake_pool.calls) == 1


async def test_create_video_invalid_url_returns_400(client, fake_pool):
    r = await client.post("/videos", json={"url": "https://vimeo.com/12345"})
    assert r.status_code == 400
    assert len(fake_pool.calls) == 0


# --- GET / DELETE /videos/{id} ---

async def test_get_video_404_for_missing(client):
    r = await client.get("/videos/deadbeef0000")
    assert r.status_code == 404


async def test_delete_video_removes_db_row(client):
    await db.create_video("abc123def456", "dQw4w9WgXcQ", YT_URL)
    r = await client.delete("/videos/abc123def456")
    assert r.status_code == 200
    assert r.json() == {"detail": "deleted"}
    assert await db.get_video("abc123def456") is None

    r = await client.delete("/videos/abc123def456")
    assert r.status_code == 404


# --- GET /videos/{id}/subtitles/{lang}/text ---

async def test_subtitle_text_invalid_lang_rejected(client):
    await db.create_video("abc123def456", "dQw4w9WgXcQ", YT_URL)
    # Path traversal attempt: must never reach the filesystem. Depending on
    # URL normalization this is either rejected by _LANG_RE (400) or falls
    # off the route entirely (404) - both block the traversal.
    r = await client.get("/videos/abc123def456/subtitles/..%2Fetc/text")
    assert r.status_code in (400, 404)  # Starlette: %2F never matches {lang}

    # A dotted lang that DOES reach the handler is rejected by _LANG_RE.
    r = await client.get("/videos/abc123def456/subtitles/../text")
    assert r.status_code in (400, 404)
    r = await client.get("/videos/abc123def456/subtitles/a.b/text")
    assert r.status_code == 400

    r = await client.get(f"/videos/abc123def456/subtitles/{'x' * 20}/text")
    assert r.status_code == 400
    assert r.json()["detail"] == "Invalid language code"


async def test_subtitle_text_valid_lang_missing_file_404(client):
    await db.create_video("abc123def456", "dQw4w9WgXcQ", YT_URL)
    r = await client.get("/videos/abc123def456/subtitles/en/text")
    assert r.status_code == 404
    assert "not found" in r.json()["detail"].lower()


async def test_subtitle_text_strips_vtt_formatting(client, data_dir):
    await db.create_video("abc123def456", "dQw4w9WgXcQ", YT_URL)
    subs_dir = data_dir / "videos" / "abc123def456" / "subs"
    subs_dir.mkdir(parents=True, exist_ok=True)
    (subs_dir / "en.vtt").write_text(
        "WEBVTT\n\n1\n00:00:00.000 --> 00:00:02.000\nHello there\n\nNOTE internal\n\n2\n"
        "00:00:02.000 --> 00:00:04.000\nGeneral Kenobi\n"
    )
    r = await client.get("/videos/abc123def456/subtitles/en/text")
    assert r.status_code == 200
    assert r.text == "Hello there\nGeneral Kenobi"


# --- static files must not expose the SQLite DB ---

async def test_sqlite_db_not_served_over_http(client, data_dir):
    dummy = data_dir / "video-clips.db"
    if not dummy.exists():
        dummy.write_bytes(b"sqlite dummy")
    r = await client.get("/files/video-clips.db")
    assert r.status_code == 404
    r = await client.get("/files/../video-clips.db")
    assert r.status_code in (400, 404)

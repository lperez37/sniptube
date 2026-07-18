from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.staticfiles import StaticFiles

from app.config import settings
from app.database import init_db


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    from app.database import fail_stale_jobs
    await fail_stale_jobs()
    yield
    from app.queue import close_arq_pool
    await close_arq_pool()


app = FastAPI(
    title="Sniptube",
    version="0.1.0",
    lifespan=lifespan,
    description="""Sniptube — self-hosted YouTube clip and GIF generator API.

## Overview

Download YouTube videos once with yt-dlp, then create lossless MP4 clips, frame-accurate cuts, high-quality GIFs, and audio extracts on demand. All processing uses ffmpeg.

## Workflow

1. **Download** a video via `POST /videos` with a YouTube URL
2. **Poll** the returned job via `GET /jobs/{job_id}` until status is `completed`
3. **Create derivatives** (clips, GIFs, audio, resolution downloads) via the respective endpoints
4. **Poll** derivative jobs the same way — completed jobs include a `result_url` for download
5. **Browse** all generated files via `GET /videos/{video_id}/derivatives`

## Key Concepts

- **Video ID**: Deterministic SHA-256 hash of the YouTube video ID (12 hex chars). Same URL always produces the same ID.
- **Job**: Every async operation (download, clip, GIF, audio, redownload) creates a job you poll for progress.
- **Derivative**: A generated file (clip, GIF, audio, redownloaded video) linked to a source video.
- **Params Hash**: Derivatives are cached by a hash of their parameters. Identical requests return the cached file instantly.

## Files

Generated files are served at `/files/videos/{video_id}/...`. The `result_url` in job/derivative responses is a ready-to-use path.
""",
    openapi_tags=[
        {
            "name": "search",
            "description": "Search YouTube for videos by keyword. Results include metadata and are cross-referenced with your local library.",
        },
        {
            "name": "videos",
            "description": "Download YouTube videos, list your library, get metadata, manage subtitles, and delete videos. Start here — you need a downloaded video before creating any derivatives.",
        },
        {
            "name": "clips",
            "description": "Create MP4 clips, GIFs, audio extracts, and resolution downloads from downloaded videos. Also list and delete generated derivatives.",
        },
        {
            "name": "jobs",
            "description": "Poll job status and progress. Every async operation returns a job ID — use this endpoint to track completion and get the result URL.",
        },
    ],
)

app.add_middleware(GZipMiddleware, minimum_size=1024)


# Derivative files are content-addressed by params hash and never change, so
# browsers may cache them forever - repeat clip/GIF previews cost zero requests.
@app.middleware("http")
async def derivative_cache_headers(request: Request, call_next):
    response = await call_next(request)
    path = request.url.path
    if (
        path.startswith("/files/videos/")
        and "/derivatives/" in path
        and response.status_code in (200, 206)
    ):
        response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
    return response


# The bundled UI is served same-origin, so CORS is off by default. Set
# CORS_ORIGINS (comma-separated) only if a browser app on another origin
# needs to call this API.
if settings.cors_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[o.strip() for o in settings.cors_origins.split(",") if o.strip()],
        allow_methods=["*"],
        allow_headers=["*"],
    )

# Import and mount routers
from app.routers import clips, jobs, search, videos  # noqa: E402

app.include_router(search.router, prefix="/search", tags=["search"])
app.include_router(videos.router, prefix="/videos", tags=["videos"])
app.include_router(jobs.router, prefix="/jobs", tags=["jobs"])
app.include_router(clips.router, tags=["clips"])

@app.get("/share-target", include_in_schema=False)
async def share_target(title: str = "", text: str = "", url: str = ""):
    """Web Share Target: receives shares from Android (e.g. the YouTube app).

    The YouTube app puts the video URL in `text`; other apps may use `url`.
    Extract the first http(s) URL and hand it to the SPA via the hash, which
    auto-downloads YouTube links and searches anything else.
    """
    import re as _re
    from urllib.parse import quote

    from fastapi.responses import RedirectResponse

    candidates = f"{url} {text} {title}"
    match = _re.search(r"https?://\S+", candidates)
    shared = match.group(0) if match else (text or title).strip()
    return RedirectResponse(f"/#/download?share={quote(shared)}", status_code=303)


# Serve generated files. Mount only the videos subtree - the SQLite database
# also lives in data_dir and must never be reachable over HTTP.
app.mount(
    "/files/videos",
    StaticFiles(directory=str(settings.data_dir / "videos")),
    name="files",
)

# Serve UI
ui_dir = Path("/app/ui")
if ui_dir.exists():
    app.mount("/", StaticFiles(directory=str(ui_dir), html=True), name="ui")

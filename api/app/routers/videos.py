import asyncio
import json
import re
import shutil

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse, PlainTextResponse

from app.config import settings
from app.database import create_job, create_video, delete_video, get_active_job, get_all_derivative_stats, get_derivative_result_paths, get_video, list_expired_unprotected, list_videos, set_video_protected, update_video
from app.models import ProtectedRequest, VideoCreate, VideoCreateResponse, VideoResponse
from app.queue import enqueue_or_fail
from app.utils.ids import extract_youtube_id, make_job_id, make_video_id, slugify
from app.utils.validate import validate_youtube_url
from app.utils.youtube import youtube_ydl_opts

router = APIRouter()

_LANG_RE = re.compile(r"^[A-Za-z0-9_-]{2,10}$")


def _source_file_size(video_id: str) -> int | None:
    """Get source video file size in bytes, or None if not found."""
    video_dir = settings.data_dir / "videos" / video_id
    for ext in ("mp4", "mkv", "webm"):
        source = video_dir / f"source.{ext}"
        if source.exists():
            return source.stat().st_size
    return None


def _read_meta_fields(video_id: str) -> dict:
    """Read available_heights and source_height from meta.json."""
    meta_path = settings.data_dir / "videos" / video_id / "meta.json"
    if meta_path.exists():
        try:
            meta = json.loads(meta_path.read_text())
            return {
                "available_heights": meta.get("available_heights", []),
                "source_height": meta.get("source_height"),
            }
        except (json.JSONDecodeError, OSError):
            pass
    return {"available_heights": [], "source_height": None}


@router.post("", response_model=VideoCreateResponse,
             summary="Download a YouTube video",
             response_description="Returns the video ID and a job ID to poll for progress")
async def create_video_endpoint(body: VideoCreate):
    """Download a YouTube video by URL using yt-dlp.

    Accepts `youtube.com` and `youtu.be` URLs (including mobile `m.youtube.com`).
    The video ID is deterministic — submitting the same URL twice returns `already_exists`
    instead of re-downloading.

    **After calling this endpoint**, poll `GET /jobs/{job_id}` until the status
    becomes `completed` or `failed`. Once completed, the video appears in your library.
    """
    try:
        validate_youtube_url(body.url)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    try:
        youtube_id = extract_youtube_id(body.url)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    video_id = make_video_id(body.url)

    # Check if already exists and ready
    existing = await get_video(video_id)
    if existing and existing["status"] == "ready":
        return VideoCreateResponse(video_id=video_id, job_id="", status="already_exists")

    # A download is already in flight: return its job instead of enqueuing a
    # second one that would write the same source file concurrently.
    if existing and existing["status"] == "downloading":
        active = await get_active_job(video_id, "download")
        if active:
            return VideoCreateResponse(video_id=video_id, job_id=active["id"], status="queued")

    # Create video record (or reset a previously failed one)
    await create_video(video_id, youtube_id, body.url)
    if existing:
        await update_video(video_id, status="downloading")

    # Enqueue download job
    job_id = make_job_id()
    await create_job(job_id, video_id, "download", {"url": body.url})
    await enqueue_or_fail("download_video", job_id, video_id, body.url)

    return VideoCreateResponse(video_id=video_id, job_id=job_id, status="queued")


@router.get("", response_model=list[VideoResponse],
            summary="List all videos in the library",
            response_description="Array of video objects sorted by creation date (newest first)")
async def list_videos_endpoint():
    """Return all downloaded videos with metadata.

    Each video includes its title, duration, language, thumbnail URL, available
    subtitle languages, and current status (`downloading`, `ready`, or `failed`).
    Only videos with status `ready` can be used to create derivatives.
    """
    videos = await list_videos()
    deriv_stats = await get_all_derivative_stats()

    def build_results() -> list[VideoResponse]:
        # File stat() calls block; run the whole loop off the event loop.
        results = []
        for v in videos:
            stats = deriv_stats.get(v["id"], {"count": 0, "paths": []})
            total_size = 0
            for p in stats["paths"]:
                fp = settings.data_dir / p
                if fp.exists():
                    total_size += fp.stat().st_size
            src_size = _source_file_size(v["id"])
            meta_fields = _read_meta_fields(v["id"])
            results.append(VideoResponse(**v, file_size=src_size, derivatives_count=stats["count"], derivatives_total_size=total_size, **meta_fields))
        return results

    return await asyncio.to_thread(build_results)


@router.post("/prune",
             summary="Prune old unprotected videos",
             response_description="Number of videos deleted")
async def prune_videos():
    """Delete unprotected videos older than TRIM_AFTER_DAYS. Protected videos are never deleted."""
    expired = await list_expired_unprotected(settings.trim_after_days)
    deleted = 0
    for video in expired:
        vid = video["id"]
        video_dir = settings.data_dir / "videos" / vid
        if video_dir.exists():
            await asyncio.to_thread(shutil.rmtree, video_dir)
        await delete_video(vid)
        deleted += 1
    return {"deleted": deleted}


@router.get("/{video_id}", response_model=VideoResponse,
            summary="Get video details",
            response_description="Video metadata including title, duration, and available subtitles")
async def get_video_endpoint(video_id: str):
    """Return full metadata for a single video.

    The `video_id` is a 12-character hex string derived from the YouTube video ID.
    """
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    paths = await get_derivative_result_paths(video_id)
    total_size = 0
    for p in paths:
        fp = settings.data_dir / p
        if fp.exists():
            total_size += fp.stat().st_size
    src_size = _source_file_size(video_id)
    meta_fields = _read_meta_fields(video_id)
    return VideoResponse(**video, file_size=src_size, derivatives_count=len(paths), derivatives_total_size=total_size, **meta_fields)


@router.delete("/{video_id}",
               summary="Delete a video and all its files",
               response_description="Confirmation of deletion")
async def delete_video_endpoint(video_id: str):
    """Permanently delete a video, its source file, all derivatives (clips, GIFs, audio, redownloads), and subtitle files.

    This action cannot be undone. The video must be re-downloaded to use it again.
    """
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")

    # Delete files on disk
    video_dir = settings.data_dir / "videos" / video_id
    if video_dir.exists():
        await asyncio.to_thread(shutil.rmtree, video_dir)

    await delete_video(video_id)
    return {"detail": "deleted"}


@router.patch("/{video_id}/protected",
              summary="Toggle video protection",
              response_description="Updated video object")
async def toggle_protected(video_id: str, body: ProtectedRequest):
    """Set or unset protection on a video. Protected videos survive auto-pruning."""
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    updated = await set_video_protected(video_id, body.protected)
    return {"protected": bool(updated["protected"])}


@router.get("/{video_id}/source",
            summary="Download original source video",
            response_description="The source video file as a download")
async def download_source(video_id: str):
    """Stream the original source video file as a download.

    The file is the full-quality video as downloaded by yt-dlp (typically AV1/VP9 in an MP4 container).
    The response includes a `Content-Disposition` header with the video title as filename.
    """
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")

    video_dir = settings.data_dir / "videos" / video_id
    for ext in ("mp4", "mkv", "webm"):
        source = video_dir / f"source.{ext}"
        if source.exists():
            filename = f"{slugify(video.get('title') or video_id)}.{ext}"
            return FileResponse(
                source,
                media_type="application/octet-stream",
                filename=filename,
            )

    raise HTTPException(status_code=404, detail="Source file not found")


@router.get("/{video_id}/subtitles",
            summary="List subtitle tracks",
            response_description="Array of available subtitle tracks with language codes and VTT file URLs")
async def list_subtitles(video_id: str):
    """List all available subtitle tracks for a video.

    Each track has a `language` code (e.g. `en`, `es`) and a `url` pointing to the WebVTT file.
    Subtitles are fetched automatically during download when available. Use `POST .../subtitles/fetch` to retry if they were missed.
    """
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")

    subs_dir = settings.data_dir / "videos" / video_id / "subs"
    tracks = []
    if subs_dir.exists():
        for vtt_file in sorted(subs_dir.glob("*.vtt")):
            lang = vtt_file.stem
            tracks.append({
                "language": lang,
                "url": f"/files/videos/{video_id}/subs/{lang}.vtt",
            })

    return tracks


@router.post("/{video_id}/subtitles/fetch",
             summary="Fetch or re-fetch subtitles from YouTube",
             response_description="List of all available languages and which were newly fetched")
async def fetch_subtitles_endpoint(video_id: str):
    """Fetch subtitles from YouTube using youtube-transcript-api (with yt-dlp fallback).

    Useful when subtitles failed during the initial download (e.g. due to YouTube rate limiting).
    Existing subtitle files are not overwritten. Returns both the full list of languages
    and which ones were newly fetched.
    """
    from app.utils.subtitles import fetch_subtitles
    from app.database import update_video

    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    if video["status"] != "ready":
        raise HTTPException(status_code=409, detail="Video is not ready yet")

    youtube_id = video.get("youtube_id")
    if not youtube_id:
        raise HTTPException(status_code=400, detail="Missing youtube_id in video record")

    subs_dir = settings.data_dir / "videos" / video_id / "subs"
    original_lang = video.get("language") or "en"
    wanted = [original_lang]
    if original_lang != "en":
        wanted.append("en")

    try:
        # fetch_subtitles does sequential network I/O (transcript API + yt-dlp
        # fallback) - run in a thread so the event loop keeps serving requests.
        saved = await asyncio.to_thread(fetch_subtitles, youtube_id, subs_dir, wanted)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Subtitle fetch failed: {e}")

    # Merge with any existing subtitles
    existing = video.get("subtitles", []) or []
    all_langs = list(dict.fromkeys(existing + saved))  # dedupe, preserve order
    await update_video(video_id, subtitles=all_langs)

    return {"languages": all_langs, "fetched": saved}


@router.get("/{video_id}/subtitles/{lang}/text",
            summary="Get subtitle text (plain text)",
            response_description="Plain text transcript with VTT formatting stripped")
async def get_subtitle_text(video_id: str, lang: str):
    """Return the subtitle content as plain text with all VTT formatting removed.

    Timestamps, cue identifiers, and headers are stripped — only the spoken text remains.
    Useful for transcript display, search indexing, or feeding to language models.
    The `lang` parameter is the language code (e.g. `en`, `es`).
    """
    if not _LANG_RE.match(lang):
        raise HTTPException(status_code=400, detail="Invalid language code")

    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")

    vtt_path = settings.data_dir / "videos" / video_id / "subs" / f"{lang}.vtt"
    if not vtt_path.exists():
        raise HTTPException(status_code=404, detail=f"Subtitle track '{lang}' not found")

    raw = vtt_path.read_text(encoding="utf-8")
    lines = []
    for line in raw.splitlines():
        line = line.strip()
        # Skip VTT header, timestamps, cue identifiers, and blank lines
        if not line or line == "WEBVTT" or "-->" in line or re.match(r"^\d+$", line):
            continue
        # Skip NOTE blocks
        if line.startswith("NOTE"):
            continue
        lines.append(line)

    return PlainTextResponse("\n".join(lines))


@router.post("/{video_id}/probe",
             summary="Probe YouTube for available resolutions",
             response_description="List of available video heights")
async def probe_resolutions(video_id: str):
    """Fetch available resolutions from YouTube without downloading.

    Useful for backfilling `available_heights` on videos downloaded before
    this feature was added. Updates meta.json with the results.
    """
    import yt_dlp

    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    if video["status"] != "ready":
        raise HTTPException(status_code=409, detail="Video is not ready yet")

    # Already have heights?
    existing = _read_meta_fields(video_id)
    if existing["available_heights"]:
        return {"available_heights": existing["available_heights"], "source_height": existing["source_height"]}

    url = video.get("url")
    if not url:
        raise HTTPException(status_code=400, detail="Missing URL in video record")

    def _probe() -> dict:
        with yt_dlp.YoutubeDL(youtube_ydl_opts()) as ydl:
            return ydl.extract_info(url, download=False)

    try:
        # Multi-second network call - run in a thread so the event loop
        # keeps serving requests (same pattern as routers/search.py).
        info = await asyncio.to_thread(_probe)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"yt-dlp probe failed: {e}")

    heights = sorted(
        {
            f["height"]
            for f in (info.get("formats") or [])
            if f.get("height") and f.get("vcodec") and f["vcodec"] != "none"
        },
        reverse=True,
    )

    source_height = info.get("height")

    # Update meta.json
    meta_path = settings.data_dir / "videos" / video_id / "meta.json"
    if meta_path.exists():
        try:
            meta = json.loads(meta_path.read_text())
            meta["available_heights"] = heights
            if source_height and not meta.get("source_height"):
                meta["source_height"] = source_height
            meta_path.write_text(json.dumps(meta, indent=2))
        except (json.JSONDecodeError, OSError):
            pass

    return {"available_heights": heights, "source_height": source_height}

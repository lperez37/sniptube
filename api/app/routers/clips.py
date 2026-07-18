from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

from app.config import settings
from app.database import create_job, delete_job, get_active_job, get_job, get_video, list_jobs_for_video
from app.models import AudioRequest, ClipRequest, DerivativeResponse, GifRequest, JobResponse, RedownloadRequest
from app.queue import enqueue_or_fail
from app.utils.ids import make_job_id, slugify

router = APIRouter()


def _job_response(job: dict) -> JobResponse:
    return JobResponse(
        id=job["id"],
        video_id=job["video_id"],
        type=job["type"],
        params=job["params"],
        status=job["status"],
        progress=job["progress"],
        created_at=job["created_at"],
        updated_at=job["updated_at"],
    )


@router.post("/videos/{video_id}/clips", response_model=JobResponse,
             summary="Create an MP4 clip",
             response_description="Job object — poll GET /jobs/{id} for progress and result URL")
async def create_clip_endpoint(video_id: str, body: ClipRequest):
    """Create an MP4 clip from a time range of the source video.

    **Modes:**
    - `copy` — Lossless stream copy. Instant, but cuts on nearest keyframe (±1-2s imprecision). Best for quick extracts.
    - `precise` — Frame-accurate re-encode with CRF 18 (visually lossless). Slower but exact timing. Required when using crop.

    **Crop:** Set `crop_pct` (10-100) to crop to the center N% of the frame. This automatically forces `precise` mode.

    Results are cached — requesting the same parameters returns the cached file instantly.
    """
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    if video["status"] != "ready":
        raise HTTPException(status_code=409, detail="Video is not ready yet")

    if body.start_sec >= body.end_sec:
        raise HTTPException(status_code=400, detail="start_sec must be less than end_sec")

    if video.get("duration") and body.end_sec > video["duration"]:
        raise HTTPException(status_code=400, detail=f"end_sec exceeds video duration ({video['duration']}s)")

    mode = body.mode
    if body.crop_pct is not None:
        mode = "precise"

    job_id = make_job_id()
    params = {"start_sec": body.start_sec, "end_sec": body.end_sec, "mode": mode}
    if body.crop_pct is not None:
        params["crop_pct"] = body.crop_pct
    job = await create_job(job_id, video_id, "clip", params)
    await enqueue_or_fail("create_clip", job_id, video_id,
                          body.start_sec, body.end_sec, mode, body.crop_pct)
    return _job_response(job)


@router.post("/videos/{video_id}/gifs", response_model=JobResponse,
             summary="Create an animated GIF",
             response_description="Job object — poll GET /jobs/{id} for progress and result URL")
async def create_gif_endpoint(video_id: str, body: GifRequest):
    """Create an animated GIF from a time range of the source video.

    **Quality modes:**
    - `high` — Two-pass with optimized palette (palettegen + paletteuse with Floyd-Steinberg dithering). Best quality, slower.
    - `fast` — Single-pass. Smaller file, lower quality.

    **Parameters:** Control output `width` (default 480px), `fps` (default 10), and optional `crop_pct`.

    Keep clips short (under 15 seconds) for reasonable GIF file sizes. Results are cached by parameters.
    """
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    if video["status"] != "ready":
        raise HTTPException(status_code=409, detail="Video is not ready yet")

    if body.start_sec >= body.end_sec:
        raise HTTPException(status_code=400, detail="start_sec must be less than end_sec")

    if video.get("duration") and body.end_sec > video["duration"]:
        raise HTTPException(status_code=400, detail=f"end_sec exceeds video duration ({video['duration']}s)")

    job_id = make_job_id()
    params = {
        "start_sec": body.start_sec,
        "end_sec": body.end_sec,
        "width": body.width,
        "fps": body.fps,
        "quality": body.quality,
    }
    if body.crop_pct is not None:
        params["crop_pct"] = body.crop_pct
    job = await create_job(job_id, video_id, "gif", params)
    await enqueue_or_fail("create_gif", job_id, video_id,
                          body.start_sec, body.end_sec,
                          body.width, body.fps, body.quality, body.crop_pct)
    return _job_response(job)


@router.post("/videos/{video_id}/audio", response_model=JobResponse,
             summary="Extract audio as MP3",
             response_description="Job object — poll GET /jobs/{id} for progress and result URL")
async def extract_audio_endpoint(video_id: str, body: AudioRequest | None = None):
    """Extract the audio track from the video as an MP3 file (192 kbps).

    By default extracts the full video's audio. Optionally provide `start_sec` and `end_sec`
    to extract only a time range. Both must be provided together, or neither.

    Results are cached by parameters.
    """
    if body is None:
        body = AudioRequest()
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    if video["status"] != "ready":
        raise HTTPException(status_code=409, detail="Video is not ready yet")

    # Both or neither
    if (body.start_sec is None) != (body.end_sec is None):
        raise HTTPException(status_code=400, detail="Provide both start_sec and end_sec, or neither")

    if body.start_sec is not None and body.end_sec is not None:
        if body.start_sec >= body.end_sec:
            raise HTTPException(status_code=400, detail="start_sec must be less than end_sec")
        if video.get("duration") and body.end_sec > video["duration"]:
            raise HTTPException(status_code=400, detail=f"end_sec exceeds video duration ({video['duration']}s)")

    job_id = make_job_id()
    params = {}
    if body.start_sec is not None:
        params["start_sec"] = body.start_sec
        params["end_sec"] = body.end_sec
    job = await create_job(job_id, video_id, "audio", params)
    await enqueue_or_fail("extract_audio_task", job_id, video_id,
                          body.start_sec, body.end_sec)
    return _job_response(job)


@router.post("/videos/{video_id}/redownload", response_model=JobResponse,
             summary="Download video at a different resolution",
             response_description="Job object — poll GET /jobs/{id} for progress and result URL")
async def redownload_video_endpoint(video_id: str, body: RedownloadRequest):
    """Download a native YouTube stream at a specific resolution via yt-dlp.

    This downloads the actual YouTube stream at the requested height — no re-encoding.
    The source video (typically 1080p) is available via the `/source` endpoint;
    use this endpoint for other resolutions (4K, 1440p, 720p, 480p, etc.).

    Results are cached by height — requesting the same resolution returns the cached file.
    """
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    if video["status"] != "ready":
        raise HTTPException(status_code=409, detail="Video is not ready yet")

    # Same resolution already queued/running: return that job instead of
    # racing two identical downloads to the same output path.
    active = await get_active_job(video_id, "redownload")
    if active and active["params"].get("height") == body.height:
        return _job_response(active)

    job_id = make_job_id()
    params = {"height": body.height}
    job = await create_job(job_id, video_id, "redownload", params)
    await enqueue_or_fail("redownload_video", job_id, video_id, body.height)
    return _job_response(job)


@router.get("/videos/{video_id}/derivatives/{job_id}/download",
            summary="Download derivative with friendly filename",
            response_description="The derivative file with a human-readable filename")
async def download_derivative(video_id: str, job_id: str):
    """Download a derivative file with a filename based on the video title."""
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")

    job = await get_job(job_id)
    if not job or job["video_id"] != video_id:
        raise HTTPException(status_code=404, detail="Derivative not found")
    if not job.get("result_path"):
        raise HTTPException(status_code=404, detail="File not ready")

    file_path = settings.data_dir / job["result_path"]
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="File not found")

    title_slug = slugify(video.get("title") or video_id)
    ext = file_path.suffix
    deriv_type = job["type"]

    if deriv_type == "audio":
        params = job.get("params", {})
        if params.get("start_sec") is not None:
            filename = f"{title_slug}-audio-{file_path.stem[:6]}{ext}"
        else:
            filename = f"{title_slug}{ext}"
    else:
        filename = f"{title_slug}-{deriv_type}-{file_path.stem[:6]}{ext}"

    return FileResponse(
        file_path,
        media_type="application/octet-stream",
        filename=filename,
    )


@router.delete("/videos/{video_id}/derivatives/{job_id}",
               summary="Delete a single derivative",
               response_description="Confirmation of deletion")
async def delete_derivative(video_id: str, job_id: str):
    """Delete a single derivative file (clip, GIF, audio, or redownloaded video) and its job record.

    Frees disk space. The derivative can be regenerated later by re-submitting the same request.
    """
    job = await get_job(job_id)
    if not job or job["video_id"] != video_id:
        raise HTTPException(status_code=404, detail="Derivative not found")

    # Delete the file on disk
    if job.get("result_path"):
        file_path = settings.data_dir / job["result_path"]
        if file_path.exists():
            file_path.unlink()

    await delete_job(job_id)
    return {"ok": True}


@router.get("/videos/{video_id}/derivatives", response_model=list[DerivativeResponse],
            summary="List all derivatives for a video",
            response_description="Array of derivative objects with type, parameters, status, and download URLs")
async def list_derivatives(video_id: str):
    """List all generated derivatives (clips, GIFs, audio, redownloads) for a video.

    Completed derivatives include a `result_url` for direct download. Failed or in-progress
    derivatives are also included with their current status.
    """
    video = await get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")

    jobs = await list_jobs_for_video(video_id)
    results = []
    for job in jobs:
        if job["type"] not in ("clip", "gif", "audio", "redownload"):
            continue
        result_url = None
        file_size = None
        if job["status"] == "completed" and job.get("result_path"):
            result_url = f"/files/{job['result_path']}"
            file_path = settings.data_dir / job["result_path"]
            if file_path.exists():
                file_size = file_path.stat().st_size
        results.append(DerivativeResponse(
            job_id=job["id"],
            type=job["type"],
            params=job["params"],
            status=job["status"],
            result_url=result_url,
            file_size=file_size,
            created_at=job["created_at"],
        ))

    return results

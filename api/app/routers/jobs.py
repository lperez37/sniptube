from fastapi import APIRouter, HTTPException

from app.database import get_job
from app.models import JobResponse

router = APIRouter()


@router.get("/{job_id}", response_model=JobResponse,
            summary="Get job status",
            response_description="Job object with status, progress percentage, and result URL when completed")
async def get_job_endpoint(job_id: str):
    """Poll a job's current status, progress, and result.

    **Status values:** `queued` → `running` → `completed` or `failed`.

    - `progress` is 0-100 (approximate).
    - `result_url` is populated when status is `completed` — it's a direct download link (e.g. `/files/videos/.../clip.mp4`).
    - `error` is populated when status is `failed`.

    **Recommended polling interval:** 1-2 seconds. Most clips complete in under 10 seconds;
    downloads and redownload jobs may take several minutes for long videos.
    """
    job = await get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")

    result_url = None
    if job["status"] == "completed" and job.get("result_path"):
        result_url = f"/files/{job['result_path']}"

    return JobResponse(
        id=job["id"],
        video_id=job["video_id"],
        type=job["type"],
        params=job["params"],
        status=job["status"],
        progress=job["progress"],
        result_url=result_url,
        error=job.get("error"),
        created_at=job["created_at"],
        updated_at=job["updated_at"],
    )

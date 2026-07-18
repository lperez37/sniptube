from fastapi import APIRouter, HTTPException, Query

from app.database import get_job, list_active_jobs
from app.models import JobResponse

router = APIRouter()


# NOTE: must be declared before /{job_id}, or "active" would match as a job id.
@router.get("/active", response_model=list[JobResponse],
            summary="List active jobs",
            response_description="All queued or running jobs, newest first")
async def list_active_jobs_endpoint(
    type: str | None = Query(None, description="Filter by job type, e.g. 'download'"),
):
    """List all jobs currently queued or running.

    Lets clients resume progress tracking after a page reload - e.g. the web UI
    polls every download job returned here so background downloads stay visible.
    """
    jobs = await list_active_jobs(type)
    return [
        JobResponse(
            id=j["id"],
            video_id=j["video_id"],
            type=j["type"],
            params=j["params"],
            status=j["status"],
            progress=j["progress"],
            created_at=j["created_at"],
            updated_at=j["updated_at"],
        )
        for j in jobs
    ]


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

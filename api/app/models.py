from typing import Literal

from pydantic import BaseModel, Field


# --- Requests ---

class VideoCreate(BaseModel):
    """Request to download a YouTube video."""
    url: str = Field(
        ...,
        description="YouTube video URL. Supports youtube.com, youtu.be, and m.youtube.com formats.",
        json_schema_extra={"examples": ["https://www.youtube.com/watch?v=dQw4w9WgXcQ"]},
    )


class ClipRequest(BaseModel):
    """Request to create an MP4 clip from a time range."""
    start_sec: float = Field(..., ge=0, description="Start time in seconds (e.g. 30.5 for 0:30.5)")
    end_sec: float = Field(..., gt=0, description="End time in seconds (must be greater than start_sec)")
    mode: Literal["copy", "precise"] = Field(
        "copy",
        description="'copy' = lossless stream copy (fast, keyframe-aligned). "
                    "'precise' = frame-accurate re-encode (CRF 0, exact timing). "
                    "Automatically set to 'precise' when crop_pct is provided.",
    )
    crop_pct: int | None = Field(
        None, ge=10, le=100,
        description="Crop to center N% of frame. Set to 80 to remove 10% from each edge. Forces precise mode.",
    )


class GifRequest(BaseModel):
    """Request to create an animated GIF from a time range."""
    start_sec: float = Field(..., ge=0, description="Start time in seconds")
    end_sec: float = Field(..., gt=0, description="End time in seconds (keep under 15s for reasonable file sizes)")
    width: int = Field(480, ge=100, le=1920, description="Output width in pixels. Height is auto-calculated.")
    fps: int = Field(10, ge=1, le=30, description="Frames per second. 10 is good for most GIFs, 15+ for smoother motion.")
    quality: Literal["high", "fast"] = Field(
        "high",
        description="'high' = two-pass palette optimization (best quality, slower). "
                    "'fast' = single-pass (smaller file, lower quality).",
    )
    crop_pct: int | None = Field(
        None, ge=10, le=100,
        description="Crop to center N% of frame",
    )


class AudioRequest(BaseModel):
    """Request to extract audio as MP3. Omit both times for full video."""
    start_sec: float | None = Field(None, ge=0, description="Start time in seconds. Omit for full video audio.")
    end_sec: float | None = Field(None, gt=0, description="End time in seconds. Omit for full video audio.")


class RedownloadRequest(BaseModel):
    """Request to download the video at a different resolution via yt-dlp."""
    height: int = Field(..., ge=144, le=4320, description="Target vertical resolution (e.g. 2160 for 4K, 1440 for 1440p, 720 for 720p).")


class ProtectedRequest(BaseModel):
    """Request to set or unset video protection."""
    protected: bool = Field(..., description="True to protect, false to unprotect")


# --- Responses ---

class VideoResponse(BaseModel):
    """Video metadata."""
    id: str = Field(..., description="Deterministic video ID (12 hex chars, derived from YouTube video ID)")
    youtube_id: str = Field(..., description="Original YouTube video ID (e.g. 'dQw4w9WgXcQ')")
    url: str = Field(..., description="Original YouTube URL used for download")
    title: str | None = Field(None, description="Video title as reported by YouTube")
    duration: float | None = Field(None, description="Video duration in seconds")
    language: str | None = Field(None, description="Primary language of the video (e.g. 'en', 'es')")
    thumbnail_url: str | None = Field(None, description="YouTube thumbnail URL")
    subtitles: list[str] = Field([], description="List of available subtitle language codes")
    protected: bool = Field(False, description="Whether this video is protected from auto-pruning")
    status: str = Field(..., description="Video status: 'downloading', 'ready', or 'failed'")
    created_at: str = Field(..., description="ISO 8601 timestamp of when the video was added")
    file_size: int | None = Field(None, description="Source video file size in bytes")
    derivatives_count: int = Field(0, description="Number of completed derivatives")
    derivatives_total_size: int = Field(0, description="Total size of all derivative files in bytes")
    available_heights: list[int] = Field([], description="Available video resolutions (heights) from YouTube, sorted descending")
    source_height: int | None = Field(None, description="Height of the downloaded source video in pixels")


class JobResponse(BaseModel):
    """Job status and result. Poll this endpoint to track async operations."""
    id: str = Field(..., description="Unique job ID (UUID)")
    video_id: str = Field(..., description="ID of the video this job belongs to")
    type: str = Field(..., description="Job type: 'download', 'clip', 'gif', 'audio', or 'redownload'")
    params: dict = Field({}, description="Parameters used for this job (varies by type)")
    status: str = Field(..., description="Job status: 'queued' → 'running' → 'completed' or 'failed'")
    progress: int = Field(0, description="Progress percentage (0-100)")
    result_url: str | None = Field(None, description="Download URL for the result file (only when status is 'completed')")
    error: str | None = Field(None, description="Error message (only when status is 'failed')")
    created_at: str = Field(..., description="ISO 8601 timestamp of job creation")
    updated_at: str = Field(..., description="ISO 8601 timestamp of last status update")


class VideoCreateResponse(BaseModel):
    """Response from POST /videos — either a new download job or an existing video."""
    video_id: str = Field(..., description="Deterministic video ID (12 hex chars)")
    job_id: str = Field(..., description="Download job ID to poll (empty string if already_exists)")
    status: str = Field(..., description="'queued' if download started, 'already_exists' if video was already downloaded")


class DerivativeResponse(BaseModel):
    """A generated derivative file (clip, GIF, audio, or redownloaded video)."""
    job_id: str = Field(..., description="Job ID that created this derivative (use for DELETE)")
    type: str = Field(..., description="Derivative type: 'clip', 'gif', 'audio', or 'redownload'")
    params: dict = Field(..., description="Parameters used to generate this derivative")
    status: str = Field(..., description="Job status: 'completed', 'failed', 'running', or 'queued'")
    result_url: str | None = Field(None, description="Direct download URL (only when completed)")
    file_size: int | None = Field(None, description="File size in bytes (only when completed and file exists)")
    created_at: str = Field(..., description="ISO 8601 timestamp")


# --- Search ---

class SearchResult(BaseModel):
    """A single YouTube search result."""
    youtube_id: str = Field(..., description="YouTube video ID")
    url: str = Field(..., description="Full YouTube URL")
    title: str = Field(..., description="Video title")
    duration: float | None = Field(None, description="Duration in seconds")
    thumbnail_url: str | None = Field(None, description="Thumbnail URL")
    uploader: str | None = Field(None, description="Channel/uploader name")
    uploader_id: str | None = Field(None, description="Channel handle (e.g. @RickAstleyYT)")
    view_count: int | None = Field(None, description="View count")
    upload_date: str | None = Field(None, description="Upload date (YYYYMMDD)")
    description: str | None = Field(None, description="Description (truncated to 200 chars)")
    already_downloaded: bool = Field(False, description="Whether this video is already in the local library")
    video_id: str | None = Field(None, description="Local video ID if already downloaded")


class SearchResponse(BaseModel):
    """YouTube search results."""
    query: str = Field(..., description="The search query")
    results: list[SearchResult] = Field([], description="Search results")
    total_fetched: int = Field(0, description="Total results fetched before filtering")
    filters_applied: dict = Field({}, description="Filters that were applied")
    page: int = Field(1, description="1-based page number for this response")
    page_size: int = Field(12, description="Requested results per page (equals max_results)")
    has_more: bool = Field(False, description="True if another page is likely available")

import asyncio
import logging
import re
import time
from datetime import datetime, timezone
from typing import Literal

import yt_dlp
from fastapi import APIRouter, HTTPException, Query

from app.database import list_videos
from app.models import SearchResponse, SearchResult

router = APIRouter()
logger = logging.getLogger(__name__)

DURATION_RANGES = {
    "short": (0, 240),
    "medium": (240, 1200),
    "long": (1200, float("inf")),
}

MAX_SEARCH_FETCH = 60  # Hard ceiling on results fetched from yt-dlp per query
PAGE_MAX = 5  # Maximum page number (bounded by MAX_SEARCH_FETCH)

# Cache raw yt-dlp entries per query so consecutive pages slice the SAME
# result list. Without this, every page re-runs the search and YouTube's
# non-deterministic ordering makes results duplicate or vanish across pages.
_CACHE_TTL_SECONDS = 300
_CACHE_MAX_KEYS = 50
_search_cache: dict[tuple, dict] = {}


def _cache_get(key: tuple) -> dict | None:
    entry = _search_cache.get(key)
    if not entry:
        return None
    if time.monotonic() - entry["at"] > _CACHE_TTL_SECONDS:
        _search_cache.pop(key, None)
        return None
    return entry


def _cache_put(key: tuple, entries: list[dict], fetch_count: int, channel_name: str | None) -> None:
    if len(_search_cache) >= _CACHE_MAX_KEYS:
        oldest = min(_search_cache, key=lambda k: _search_cache[k]["at"])
        _search_cache.pop(oldest, None)
    _search_cache[key] = {
        "at": time.monotonic(),
        "entries": entries,
        "fetch_count": fetch_count,
        # yt-dlp returned fewer than asked: there is nothing more to fetch.
        "exhausted": len(entries) < fetch_count,
        "channel_name": channel_name,
    }

# Matches @handle (with or without youtube.com prefix)
_CHANNEL_RE = re.compile(r"^@[\w.-]{1,50}$")


def _is_channel_query(query: str) -> bool:
    """Return True if the query looks like a YouTube channel handle."""
    return bool(_CHANNEL_RE.match(query.strip()))


def _ydl_opts(max_results: int | None = None) -> dict:
    """Common yt-dlp options for flat extraction with approximate dates."""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": "in_playlist",
        "skip_download": True,
        "extractor_args": {"youtubetab": {"approximate_date": ["true"]}},
    }
    if max_results:
        opts["playlistend"] = max_results
    return opts


def _run_search(query: str, max_results: int) -> list[dict]:
    """Run a yt-dlp keyword search. Returns flat metadata entries."""
    with yt_dlp.YoutubeDL(_ydl_opts()) as ydl:
        result = ydl.extract_info(f"ytsearch{max_results}:{query}", download=False)
    return result.get("entries") or []


def _run_channel_search(handle: str, max_results: int) -> tuple[list[dict], str | None]:
    """List videos from a YouTube channel. Returns (entries, channel_name)."""
    url = f"https://www.youtube.com/{handle}/videos"
    with yt_dlp.YoutubeDL(_ydl_opts(max_results)) as ydl:
        result = ydl.extract_info(url, download=False)
    channel_name = result.get("channel") or result.get("uploader")
    return result.get("entries") or [], channel_name


def _upload_date_from_entry(entry: dict) -> str | None:
    """Get upload_date (YYYYMMDD) from entry, deriving from timestamp if needed."""
    ud = entry.get("upload_date")
    if ud:
        return ud
    ts = entry.get("timestamp")
    if ts:
        return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y%m%d")
    return None


def _filter_by_duration(entries: list[dict], duration: str) -> list[dict]:
    lo, hi = DURATION_RANGES[duration]
    return [e for e in entries if e.get("duration") and lo <= e["duration"] < hi]


def _sort_entries(entries: list[dict], sort_by: str) -> list[dict]:
    if sort_by == "views":
        return sorted(entries, key=lambda e: e.get("view_count") or 0, reverse=True)
    if sort_by == "date":
        # Flat keyword-search entries carry no timestamp, so date sort is only
        # meaningful for @channel queries (which are date-ordered anyway).
        return sorted(entries, key=lambda e: e.get("timestamp") or 0, reverse=True)
    return entries  # relevance / channel default order


def _entries_to_results(
    entries: list[dict],
    local_yt_ids: dict[str, str],
    channel_name: str | None = None,
) -> list[SearchResult]:
    """Convert yt-dlp entries to SearchResult models."""
    results = []
    for e in entries:
        # Skip channels, playlists, etc.
        if e.get("_type") and e["_type"] != "url":
            continue
        yt_id = e.get("id") or e.get("url", "")
        if not yt_id or len(yt_id) != 11:
            continue
        local_id = local_yt_ids.get(yt_id)
        desc = e.get("description") or ""
        results.append(SearchResult(
            youtube_id=yt_id,
            url=f"https://www.youtube.com/watch?v={yt_id}",
            title=e.get("title") or "Untitled",
            duration=e.get("duration"),
            thumbnail_url=e.get("thumbnails", [{}])[-1].get("url") if e.get("thumbnails") else e.get("thumbnail"),
            uploader=e.get("uploader") or e.get("channel") or channel_name,
            uploader_id=e.get("uploader_id"),
            view_count=e.get("view_count"),
            upload_date=_upload_date_from_entry(e),
            description=desc[:200] if desc else None,
            already_downloaded=local_id is not None,
            video_id=local_id,
        ))
    return results


@router.get("", response_model=SearchResponse,
            summary="Search YouTube videos",
            response_description="Search results with optional duration and sort filters")
async def search_youtube(
    q: str = Query(..., min_length=1, max_length=200, description="Search query or @channel handle"),
    max_results: int = Query(12, ge=1, le=30, description="Results per page"),
    page: int = Query(1, ge=1, le=PAGE_MAX, description=f"1-based page number (up to {PAGE_MAX})"),
    duration: Literal["any", "short", "medium", "long"] = Query("any", description="Duration filter: short (<4m), medium (4-20m), long (>20m)"),
    sort_by: Literal["relevance", "date", "views"] = Query("relevance", description="Sort order (date is only meaningful for @channel queries)"),
):
    """Search YouTube for videos by keyword or list a channel's videos.

    - **Keyword search**: `q=funny cats` — searches YouTube
    - **Channel listing**: `q=@RickAstleyYT` — lists the channel's videos

    Raw results are cached for 5 minutes per query, so page slices stay
    consistent while paginating. `has_more` indicates whether another page
    is likely available.

    Results include approximate upload dates, view counts, and other metadata.
    Cross-referenced with the local library — already-downloaded
    videos are flagged with `already_downloaded: true` and include their local `video_id`.
    """
    is_channel = _is_channel_query(q)
    over_fetch = 3 if duration != "any" else 1
    # Fetch one extra page so we can reliably report has_more for the current page.
    fetch_count = min((page + 1) * max_results * over_fetch, MAX_SEARCH_FETCH)

    cache_key = (q.strip().lower(), is_channel)
    cached = _cache_get(cache_key)
    if cached and (cached["fetch_count"] >= fetch_count or cached["exhausted"]):
        entries = cached["entries"]
        channel_name = cached["channel_name"]
    else:
        try:
            if is_channel:
                entries, channel_name = await asyncio.to_thread(_run_channel_search, q.strip(), fetch_count)
            else:
                entries = await asyncio.to_thread(_run_search, q, fetch_count)
                channel_name = None
        except yt_dlp.utils.DownloadError as e:
            logger.warning("yt-dlp search failed for %r: %s", q, e)
            raise HTTPException(status_code=502, detail="YouTube search failed, try again shortly")
        except Exception as e:
            logger.error("Unexpected search error for %r: %s", q, e)
            raise HTTPException(status_code=502, detail="Search failed")
        _cache_put(cache_key, entries, fetch_count, channel_name)

    total_fetched = len(entries)

    # Post-filter by duration
    if duration != "any":
        entries = _filter_by_duration(entries, duration)

    # Sort
    if sort_by != "relevance":
        entries = _sort_entries(entries, sort_by)

    # Slice to the requested page
    start = (page - 1) * max_results
    end = start + max_results
    page_entries = entries[start:end]
    has_more = len(entries) > end and page < PAGE_MAX

    # Cross-reference with local library
    local_videos = await list_videos()
    local_yt_ids = {v["youtube_id"]: v["id"] for v in local_videos}

    results = _entries_to_results(page_entries, local_yt_ids, channel_name)

    filters_applied = {}
    if duration != "any":
        filters_applied["duration"] = duration
    if sort_by != "relevance":
        filters_applied["sort_by"] = sort_by
    if is_channel:
        filters_applied["channel"] = q.strip()

    return SearchResponse(
        query=q,
        results=results,
        total_fetched=total_fetched,
        filters_applied=filters_applied,
        page=page,
        page_size=max_results,
        has_more=has_more,
    )

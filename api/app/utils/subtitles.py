"""Subtitle fetching via youtube-transcript-api (primary) with yt-dlp fallback.

Uses youtube-transcript-api for reliable, lightweight transcript retrieval.
Falls back to yt-dlp subtitle download if the API fails.
"""

import logging
from pathlib import Path

from youtube_transcript_api import YouTubeTranscriptApi

logger = logging.getLogger(__name__)


def _segments_to_vtt(segments: list[dict]) -> str:
    """Convert transcript segments to WebVTT format."""
    lines = ["WEBVTT", ""]
    for i, seg in enumerate(segments, 1):
        start = seg["start"]
        end = start + seg.get("duration", 0)
        lines.append(str(i))
        lines.append(f"{_format_ts(start)} --> {_format_ts(end)}")
        lines.append(seg["text"])
        lines.append("")
    return "\n".join(lines)


def _format_ts(seconds: float) -> str:
    """Format seconds as HH:MM:SS.mmm for VTT."""
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = seconds % 60
    return f"{h:02d}:{m:02d}:{s:06.3f}"


def fetch_subtitles(youtube_id: str, subs_dir: Path, languages: list[str]) -> list[str]:
    """Fetch subtitles for a YouTube video and save as VTT files.

    Tries youtube-transcript-api first (preferred), falls back to yt-dlp.
    Returns list of language codes that were successfully saved.
    """
    subs_dir.mkdir(parents=True, exist_ok=True)
    saved = []

    # --- Primary: youtube-transcript-api ---
    try:
        saved = _fetch_via_transcript_api(youtube_id, subs_dir, languages)
        if saved:
            logger.info("Subtitles via transcript-api for %s: %s", youtube_id, saved)
            return saved
    except Exception as e:
        logger.warning("transcript-api failed for %s: %s", youtube_id, e)

    # --- Fallback: yt-dlp ---
    try:
        saved = _fetch_via_ytdlp(youtube_id, subs_dir, languages)
        if saved:
            logger.info("Subtitles via yt-dlp for %s: %s", youtube_id, saved)
            return saved
    except Exception as e:
        logger.warning("yt-dlp subtitle fallback failed for %s: %s", youtube_id, e)

    return saved


def _fetch_via_transcript_api(youtube_id: str, subs_dir: Path, languages: list[str]) -> list[str]:
    """Fetch transcripts using youtube-transcript-api v1.0+ and save as VTT."""
    saved = []
    ytt_api = YouTubeTranscriptApi()

    # List available transcripts
    transcript_list = ytt_api.list(youtube_id)

    available = {}
    for transcript in transcript_list:
        code = transcript.language_code
        existing = available.get(code)
        # Prefer manual (human-uploaded) over auto-generated when both exist.
        if existing is None or (existing.is_generated and not transcript.is_generated):
            available[code] = transcript

    for lang in languages:
        if lang in saved:
            continue
        dest = subs_dir / f"{lang}.vtt"
        if dest.exists():
            saved.append(lang)
            continue

        transcript = None

        # Exact match
        if lang in available:
            transcript = available[lang]
        else:
            # Try prefix match (e.g. "es-US" -> "es", or "en" -> "en-US")
            for code, t in available.items():
                if code.startswith(lang) or lang.startswith(code):
                    transcript = t
                    break

        if transcript is None:
            # Try translation from any available transcript
            try:
                first = next(iter(available.values()), None)
                if first and first.is_translatable:
                    trans_codes = [tl.language_code for tl in first.translation_languages]
                    target = None
                    if lang in trans_codes:
                        target = lang
                    else:
                        for tc in trans_codes:
                            if tc.startswith(lang) or lang.startswith(tc):
                                target = tc
                                break
                    if target:
                        translated = first.translate(target)
                        fetched = translated.fetch()
                        raw = [{"text": s.text, "start": s.start, "duration": s.duration}
                               for s in fetched]
                        dest.write_text(_segments_to_vtt(raw), encoding="utf-8")
                        saved.append(lang)
                        logger.info("Saved translated subtitle %s for %s", lang, youtube_id)
                        continue
            except Exception as e:
                logger.debug("Translation to %s failed for %s: %s", lang, youtube_id, e)
            continue

        try:
            fetched = transcript.fetch()
            raw = [{"text": s.text, "start": s.start, "duration": s.duration}
                   for s in fetched]
            dest.write_text(_segments_to_vtt(raw), encoding="utf-8")
            saved.append(lang)
            logger.info("Saved subtitle %s for %s (generated=%s)", lang, youtube_id, transcript.is_generated)
        except Exception as e:
            logger.warning("Failed to fetch transcript %s for %s: %s", lang, youtube_id, e)

    return saved


def _fetch_via_ytdlp(youtube_id: str, subs_dir: Path, languages: list[str]) -> list[str]:
    """Fallback: fetch subtitles via yt-dlp (no video download)."""
    import yt_dlp

    url = f"https://www.youtube.com/watch?v={youtube_id}"
    tmp_dir = subs_dir.parent  # video_dir

    sub_opts = {
        "writesubtitles": True,
        "writeautomaticsub": True,
        "subtitlesformat": "vtt",
        "subtitleslangs": languages,
        "skip_download": True,
        "outtmpl": str(tmp_dir / "source.%(ext)s"),
        "quiet": True,
        "no_warnings": True,
    }

    with yt_dlp.YoutubeDL(sub_opts) as ydl:
        ydl.download([url])

    # Collect any VTT files written to video_dir
    saved = []
    for vtt_file in tmp_dir.glob("*.vtt"):
        parts = vtt_file.stem.split(".")
        lang = parts[-1] if len(parts) >= 2 else "unknown"
        dest = subs_dir / f"{lang}.vtt"
        if not dest.exists():
            vtt_file.rename(dest)
        else:
            vtt_file.unlink()
        if lang not in saved:
            saved.append(lang)

    return saved

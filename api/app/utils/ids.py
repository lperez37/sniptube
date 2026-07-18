import hashlib
import re
import unicodedata
import uuid
from urllib.parse import parse_qs, urlparse


def extract_youtube_id(url: str) -> str:
    """Extract the YouTube video ID from a URL."""
    parsed = urlparse(url)
    hostname = parsed.hostname or ""

    if hostname in ("youtu.be", "www.youtu.be"):
        video_id = parsed.path.lstrip("/").split("/")[0]
    elif hostname in ("youtube.com", "www.youtube.com", "m.youtube.com"):
        if parsed.path == "/watch":
            video_id = parse_qs(parsed.query).get("v", [""])[0]
        elif parsed.path.startswith(("/embed/", "/v/", "/shorts/")):
            video_id = parsed.path.split("/")[2]
        else:
            video_id = ""
    else:
        video_id = ""

    if not video_id or not re.match(r"^[A-Za-z0-9_-]{11}$", video_id):
        raise ValueError(f"Could not extract YouTube video ID from: {url}")

    return video_id


def make_video_id(url: str) -> str:
    """Deterministic videoId from a YouTube URL: SHA-256 of the YouTube video ID, truncated to 12 hex chars."""
    yt_id = extract_youtube_id(url)
    return hashlib.sha256(yt_id.encode()).hexdigest()[:12]


def make_job_id() -> str:
    """Generate a new random job ID."""
    return str(uuid.uuid4())


def make_params_hash(**params) -> str:
    """Deterministic hash of derivative parameters for caching."""
    key = "|".join(f"{k}={v}" for k, v in sorted(params.items()))
    return hashlib.sha256(key.encode()).hexdigest()[:10]


def slugify(text: str) -> str:
    """Convert text to a filename-friendly slug with hyphens."""
    text = unicodedata.normalize('NFKD', text)
    text = text.encode('ascii', 'ignore').decode('ascii')
    text = text.lower()
    text = re.sub(r'[^a-z0-9]+', '-', text)
    text = re.sub(r'-+', '-', text).strip('-')
    return text or 'video'

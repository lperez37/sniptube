from urllib.parse import urlparse

ALLOWED_HOSTS = {
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "youtu.be",
    "www.youtu.be",
}


def validate_youtube_url(url: str) -> str:
    """Validate that a URL points to YouTube. Returns the cleaned URL or raises ValueError."""
    try:
        parsed = urlparse(url)
    except Exception:
        raise ValueError("Invalid URL format")

    if parsed.scheme not in ("http", "https"):
        raise ValueError("URL must use http or https")

    hostname = parsed.hostname or ""
    if hostname not in ALLOWED_HOSTS:
        raise ValueError(f"Only YouTube URLs are accepted (got: {hostname})")

    return url

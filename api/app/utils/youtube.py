from typing import Any


YOUTUBE_EXTRACTOR_ARGS: dict[str, dict[str, list[str]]] = {"youtube": {"player_client": ["android", "web"]}}


def youtube_ydl_opts(**overrides: Any) -> dict[str, Any]:
    """Default yt-dlp options for YouTube requests.

    Some videos only expose playable HTTPS formats via non-default clients.
    Trying Android plus web keeps normal downloads working while covering those
    SABR-limited sessions.
    """
    opts = {
        "quiet": True,
        "no_warnings": True,
        "extractor_args": YOUTUBE_EXTRACTOR_ARGS,
    }
    opts.update(overrides)
    return opts

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
        # A watch URL carrying a list= param must download one video, not the
        # whole playlist (each entry would overwrite source.%(ext)s in turn).
        "noplaylist": True,
        # DASH/HLS fragments download over one connection by default;
        # parallel fragments typically speed downloads up 2-4x.
        "concurrent_fragments": 4,
    }
    opts.update(overrides)
    return opts

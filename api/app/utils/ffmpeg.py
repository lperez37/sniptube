import asyncio
import logging
from pathlib import Path

logger = logging.getLogger(__name__)


async def run_ffmpeg(args: list[str]) -> None:
    """Run an ffmpeg command safely (no shell). Raises on failure."""
    cmd = ["ffmpeg", "-y", *args]
    logger.info("Running: %s", " ".join(cmd))
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    _, stderr = await proc.communicate()
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg failed (code {proc.returncode}): {stderr.decode()[-500:]}")


def _crop_filter(crop_pct: int) -> str:
    """Return an ffmpeg crop filter that keeps the center crop_pct% of the frame.

    Uses trunc(…/2)*2 to guarantee even dimensions (required by libx264).
    Explicit (iw-ow)/2 centering for clarity.
    """
    frac = crop_pct / 100
    return (
        f"crop=trunc(iw*{frac}/2)*2:trunc(ih*{frac}/2)*2"
        f":(iw-trunc(iw*{frac}/2)*2)/2:(ih-trunc(ih*{frac}/2)*2)/2"
    )


async def make_clip_copy(source: Path, output: Path, start_sec: float, end_sec: float) -> None:
    """Lossless stream copy clip. Cuts on nearest keyframe."""
    await run_ffmpeg([
        "-ss", str(start_sec),
        "-to", str(end_sec),
        "-i", str(source),
        "-c", "copy",
        "-movflags", "+faststart",
        str(output),
    ])


async def make_clip_precise(source: Path, output: Path, start_sec: float, end_sec: float,
                            crop_pct: int | None = None) -> None:
    """Frame-accurate clip with visually lossless re-encode at boundaries."""
    vf_parts = []
    if crop_pct is not None:
        vf_parts.append(_crop_filter(crop_pct))

    args = [
        "-i", str(source),
        "-ss", str(start_sec),
        "-to", str(end_sec),
    ]
    if vf_parts:
        args += ["-vf", ",".join(vf_parts)]
    args += [
        "-c:v", "libx264",
        "-crf", "0",
        "-preset", "fast",
        "-c:a", "copy",
        "-movflags", "+faststart",
        str(output),
    ]
    await run_ffmpeg(args)


async def make_gif_high(source: Path, output: Path, start_sec: float, end_sec: float,
                        width: int = 480, fps: int = 10, crop_pct: int | None = None) -> None:
    """Two-pass GIF with optimized palette for high quality."""
    palette = output.with_suffix(".palette.png")

    vf_parts = []
    if crop_pct is not None:
        vf_parts.append(_crop_filter(crop_pct))
    vf_parts.append(f"fps={fps}")
    vf_parts.append(f"scale={width}:-1:flags=lanczos")
    vf_scale = ",".join(vf_parts)

    try:
        # Pass 1: generate palette
        await run_ffmpeg([
            "-ss", str(start_sec),
            "-to", str(end_sec),
            "-i", str(source),
            "-vf", f"{vf_scale},palettegen=stats_mode=diff",
            str(palette),
        ])

        # Pass 2: render GIF using palette
        await run_ffmpeg([
            "-ss", str(start_sec),
            "-to", str(end_sec),
            "-i", str(source),
            "-i", str(palette),
            "-lavfi", f"{vf_scale} [x]; [x][1:v] paletteuse=dither=floyd_steinberg",
            str(output),
        ])
    finally:
        palette.unlink(missing_ok=True)


async def make_gif_fast(source: Path, output: Path, start_sec: float, end_sec: float,
                        width: int = 480, fps: int = 10, crop_pct: int | None = None) -> None:
    """Single-pass GIF, smaller but lower quality."""
    vf_parts = []
    if crop_pct is not None:
        vf_parts.append(_crop_filter(crop_pct))
    vf_parts.append(f"fps={fps}")
    vf_parts.append(f"scale={width}:-1:flags=lanczos")

    await run_ffmpeg([
        "-ss", str(start_sec),
        "-to", str(end_sec),
        "-i", str(source),
        "-vf", ",".join(vf_parts),
        str(output),
    ])


async def extract_audio(source: Path, output: Path,
                        start_sec: float | None = None,
                        end_sec: float | None = None) -> None:
    """Extract audio as MP3 at 192 kbps. Optionally trim to a time range."""
    args = []
    if start_sec is not None and end_sec is not None:
        args += ["-ss", str(start_sec), "-to", str(end_sec)]
    args += [
        "-i", str(source),
        "-vn",
        "-c:a", "libmp3lame",
        "-b:a", "192k",
        str(output),
    ]
    await run_ffmpeg(args)

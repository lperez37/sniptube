"""Disposable endpoint contract fixtures for the Android Media3 source downloader."""

import pytest

from app.database import create_video, update_video


@pytest.mark.asyncio
@pytest.mark.parametrize("extension,signature", [
    ("mp4", b"\0\0\0\x10ftyp"),
    ("mkv", b"\x1a\x45\xdf\xa3"),
    ("webm", b"\x1a\x45\xdf\xa3"),
])
async def test_source_strong_validator_and_range_contract(client, data_dir, extension, signature):
    video_id = f"fixture-{extension}"
    await create_video(video_id, f"youtube-{extension}", "https://youtu.be/example")
    await update_video(video_id, title="Fixture", status="ready")
    directory = data_dir / "videos" / video_id
    directory.mkdir(parents=True, exist_ok=True)
    payload = signature + b"-media-fixture-" * 8
    (directory / f"source.{extension}").write_bytes(payload)

    initial = await client.get(f"/videos/{video_id}/source")
    assert initial.status_code == 200
    assert initial.content == payload
    assert initial.headers["content-type"] == "application/octet-stream"
    etag = initial.headers["etag"]
    assert etag and not etag.startswith("W/")

    probe = await client.get(f"/videos/{video_id}/source", headers={"Range": "bytes=0-31"})
    assert probe.status_code == 206
    assert probe.headers["etag"] == etag
    assert probe.headers["content-range"] == f"bytes 0-31/{len(payload)}"
    assert probe.content == payload[:32]

    resumed = await client.get(f"/videos/{video_id}/source", headers={
        "Range": f"bytes=32-{len(payload) - 1}", "If-Range": etag, "Accept-Encoding": "identity",
    })
    assert resumed.status_code == 206
    assert resumed.headers["content-range"] == f"bytes 32-{len(payload) - 1}/{len(payload)}"
    assert resumed.content == payload[32:]

    mismatch = await client.get(f"/videos/{video_id}/source", headers={
        "Range": "bytes=32-", "If-Range": '"other-representation"',
    })
    assert mismatch.status_code == 200
    assert mismatch.content == payload

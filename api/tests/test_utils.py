"""Tests for app/utils/ids.py and app/utils/validate.py."""

import re

import pytest

from app.utils.ids import extract_youtube_id, make_job_id, make_params_hash, make_video_id, slugify
from app.utils.validate import validate_youtube_url

YT_ID = "dQw4w9WgXcQ"


# --- validate_youtube_url ---

@pytest.mark.parametrize("url", [
    f"https://www.youtube.com/watch?v={YT_ID}",
    f"https://youtube.com/watch?v={YT_ID}",
    f"https://m.youtube.com/watch?v={YT_ID}",
    f"https://youtu.be/{YT_ID}",
    f"https://www.youtu.be/{YT_ID}",
    f"http://www.youtube.com/watch?v={YT_ID}",
])
def test_validate_accepts_youtube_variants(url):
    assert validate_youtube_url(url) == url


@pytest.mark.parametrize("url", [
    "https://vimeo.com/12345",
    "https://youtube.com.evil.com/watch?v=x",
    "https://evil.com/youtube.com",
    "ftp://youtube.com/watch?v=x",
    "youtube.com/watch?v=x",  # no scheme
    "",
    "not a url at all",
])
def test_validate_rejects_non_youtube(url):
    with pytest.raises(ValueError):
        validate_youtube_url(url)


# --- extract_youtube_id ---

@pytest.mark.parametrize("url", [
    f"https://www.youtube.com/watch?v={YT_ID}",
    f"https://youtube.com/watch?v={YT_ID}&t=42",
    f"https://m.youtube.com/watch?v={YT_ID}",
    f"https://youtu.be/{YT_ID}",
    f"https://youtu.be/{YT_ID}?si=abc",
    f"https://www.youtube.com/embed/{YT_ID}",
    f"https://www.youtube.com/v/{YT_ID}",
    f"https://www.youtube.com/shorts/{YT_ID}",
])
def test_extract_youtube_id_variants(url):
    assert extract_youtube_id(url) == YT_ID


@pytest.mark.parametrize("url", [
    "https://www.youtube.com/watch",  # no v param
    "https://www.youtube.com/playlist?list=PL123",
    "https://youtu.be/short",  # not 11 chars
    "https://youtu.be/waaaaaaaaaytoolong",
    "https://vimeo.com/dQw4w9WgXcQ",  # wrong host
    "https://www.youtube.com/watch?v=bad id here",  # invalid chars
])
def test_extract_youtube_id_rejects(url):
    with pytest.raises(ValueError):
        extract_youtube_id(url)


# --- make_video_id ---

def test_make_video_id_deterministic_across_url_forms():
    ids = {
        make_video_id(f"https://www.youtube.com/watch?v={YT_ID}"),
        make_video_id(f"https://youtu.be/{YT_ID}"),
        make_video_id(f"https://m.youtube.com/watch?v={YT_ID}&t=99"),
        make_video_id(f"https://www.youtube.com/shorts/{YT_ID}"),
    }
    assert len(ids) == 1
    vid = ids.pop()
    assert re.fullmatch(r"[0-9a-f]{12}", vid)


def test_make_video_id_differs_for_different_videos():
    a = make_video_id(f"https://youtu.be/{YT_ID}")
    b = make_video_id("https://youtu.be/AAAAAAAAAAA")
    assert a != b


# --- make_params_hash ---

def test_params_hash_stable_and_order_independent():
    h1 = make_params_hash(start=1.0, end=2.0, mode="copy")
    h2 = make_params_hash(mode="copy", end=2.0, start=1.0)
    assert h1 == h2
    assert re.fullmatch(r"[0-9a-f]{10}", h1)


def test_params_hash_changes_on_value_change():
    h1 = make_params_hash(start=1.0, end=2.0)
    h2 = make_params_hash(start=1.0, end=2.5)
    assert h1 != h2


def test_make_job_id_unique_uuid():
    a, b = make_job_id(), make_job_id()
    assert a != b
    assert len(a) == 36


def test_slugify():
    assert slugify("Héllo World! 2024") == "hello-world-2024"
    assert slugify("???") == "video"

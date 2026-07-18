"""Unit tests for helpers and the TTL cache in app/routers/search.py."""

from types import SimpleNamespace

from app.routers import search as s


def _entry(yt_id="AAAAAAAAAAA", **kw):
    e = {"id": yt_id, "title": f"title-{yt_id}"}
    e.update(kw)
    return e


# --- _is_channel_query ---

def test_channel_query_accepts_handles():
    assert s._is_channel_query("@RickAstleyYT")
    assert s._is_channel_query("  @some.handle-1_2  ")


def test_channel_query_rejects_keywords():
    assert not s._is_channel_query("funny cats")
    assert not s._is_channel_query("@")  # empty handle
    assert not s._is_channel_query("@" + "x" * 51)  # too long
    assert not s._is_channel_query("email@example.com")


# --- _filter_by_duration ---

def test_filter_by_duration_boundaries():
    entries = [
        _entry("a" * 11, duration=239),
        _entry("b" * 11, duration=240),
        _entry("c" * 11, duration=1199),
        _entry("d" * 11, duration=1200),
        _entry("e" * 11, duration=99999),
        _entry("f" * 11, duration=None),
        _entry("g" * 11),  # no duration key
    ]
    assert [e["duration"] for e in s._filter_by_duration(entries, "short")] == [239]
    assert [e["duration"] for e in s._filter_by_duration(entries, "medium")] == [240, 1199]
    assert [e["duration"] for e in s._filter_by_duration(entries, "long")] == [1200, 99999]


# --- _sort_entries ---

def test_sort_entries_by_views_missing_defaults_to_zero():
    entries = [
        _entry("a" * 11, view_count=10),
        _entry("b" * 11),  # no view_count -> 0
        _entry("c" * 11, view_count=None),  # None -> 0
        _entry("d" * 11, view_count=500),
    ]
    out = s._sort_entries(entries, "views")
    assert [e["id"][0] for e in out] == ["d", "a", "b", "c"]


def test_sort_entries_by_date_uses_timestamp():
    entries = [
        _entry("a" * 11, timestamp=100),
        _entry("b" * 11),  # missing -> 0, sorts last
        _entry("c" * 11, timestamp=300),
    ]
    out = s._sort_entries(entries, "date")
    assert [e["id"][0] for e in out] == ["c", "a", "b"]


def test_sort_entries_relevance_keeps_order():
    entries = [_entry("a" * 11, view_count=1), _entry("b" * 11, view_count=9)]
    assert s._sort_entries(entries, "relevance") == entries


# --- _entries_to_results ---

def test_entries_to_results_skips_non_video_ids_and_playlists():
    entries = [
        _entry("AAAAAAAAAAA"),
        _entry("short"),  # id not 11 chars -> skipped
        {"_type": "playlist", "id": "BBBBBBBBBBB", "title": "pl"},  # skipped
        {"_type": "url", "id": "CCCCCCCCCCC", "title": "ok"},  # kept
        {"title": "no id at all"},  # skipped
    ]
    results = s._entries_to_results(entries, {})
    assert [r.youtube_id for r in results] == ["AAAAAAAAAAA", "CCCCCCCCCCC"]
    assert results[0].url == "https://www.youtube.com/watch?v=AAAAAAAAAAA"


def test_entries_to_results_marks_already_downloaded():
    entries = [_entry("AAAAAAAAAAA"), _entry("BBBBBBBBBBB")]
    results = s._entries_to_results(entries, {"AAAAAAAAAAA": "local123"})
    assert results[0].already_downloaded is True
    assert results[0].video_id == "local123"
    assert results[1].already_downloaded is False
    assert results[1].video_id is None


def test_entries_to_results_truncates_description_to_200():
    long_desc = "x" * 500
    results = s._entries_to_results([_entry(description=long_desc)], {})
    assert len(results[0].description) == 200
    # Empty description -> None
    results = s._entries_to_results([_entry(description="")], {})
    assert results[0].description is None


def test_entries_to_results_title_and_channel_fallbacks():
    results = s._entries_to_results([{"id": "AAAAAAAAAAA"}], {}, channel_name="MyChannel")
    assert results[0].title == "Untitled"
    assert results[0].uploader == "MyChannel"


# --- TTL cache ---

def test_cache_put_get_roundtrip():
    key = ("query", False)
    entries = [_entry()]
    s._cache_put(key, entries, fetch_count=10, channel_name="chan")
    got = s._cache_get(key)
    assert got is not None
    assert got["entries"] == entries
    assert got["fetch_count"] == 10
    assert got["channel_name"] == "chan"


def test_cache_get_missing_key():
    assert s._cache_get(("nope", False)) is None


def test_cache_expiry(monkeypatch):
    clock = [1000.0]
    monkeypatch.setattr(s, "time", SimpleNamespace(monotonic=lambda: clock[0]))

    key = ("query", False)
    s._cache_put(key, [_entry()], fetch_count=1, channel_name=None)
    clock[0] += s._CACHE_TTL_SECONDS  # exactly at TTL: still valid (> comparison)
    assert s._cache_get(key) is not None
    clock[0] += 0.1  # past TTL
    assert s._cache_get(key) is None
    assert key not in s._search_cache  # expired entry evicted


def test_cache_eviction_at_max_keys(monkeypatch):
    clock = [0.0]

    def tick():
        clock[0] += 1.0
        return clock[0]

    monkeypatch.setattr(s, "time", SimpleNamespace(monotonic=tick))

    for i in range(s._CACHE_MAX_KEYS):
        s._cache_put((f"q{i}", False), [], fetch_count=1, channel_name=None)
    assert len(s._search_cache) == s._CACHE_MAX_KEYS

    s._cache_put(("overflow", False), [], fetch_count=1, channel_name=None)
    assert len(s._search_cache) == s._CACHE_MAX_KEYS
    assert ("q0", False) not in s._search_cache  # oldest evicted
    assert ("overflow", False) in s._search_cache


def test_cache_exhausted_flag():
    s._cache_put(("few", False), [_entry()] * 3, fetch_count=10, channel_name=None)
    assert s._search_cache[("few", False)]["exhausted"] is True

    s._cache_put(("full", False), [_entry()] * 10, fetch_count=10, channel_name=None)
    assert s._search_cache[("full", False)]["exhausted"] is False

"""Shared fixtures.

IMPORTANT: app.config instantiates Settings at import time and mkdirs
data_dir, so DATA_DIR must be set BEFORE any app module is imported.
This module-level code runs before pytest collects the test modules.
"""

import os
import tempfile

_DATA_DIR = tempfile.mkdtemp(prefix="sniptube-test-data-")
os.environ["DATA_DIR"] = _DATA_DIR

import pytest  # noqa: E402

from app import database  # noqa: E402
from app.routers import search as search_mod  # noqa: E402


@pytest.fixture(autouse=True)
async def clean_state():
    """Fresh DB and empty search cache for every test."""
    for f in database.DB_PATH.parent.glob("video-clips.db*"):
        f.unlink(missing_ok=True)
    await database.init_db()
    search_mod._search_cache.clear()
    yield
    search_mod._search_cache.clear()


@pytest.fixture
def data_dir():
    from app.config import settings

    return settings.data_dir


@pytest.fixture
async def client():
    from httpx import ASGITransport, AsyncClient

    from app.main import app

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


class FakeArqPool:
    def __init__(self):
        self.calls: list[tuple] = []

    async def enqueue_job(self, func_name, *args, **kwargs):
        self.calls.append((func_name, *args))
        return object()


@pytest.fixture
def fake_pool(monkeypatch):
    """Replace the arq pool so nothing touches Redis."""
    import app.queue as queue_mod

    pool = FakeArqPool()

    async def _get_pool():
        return pool

    monkeypatch.setattr(queue_mod, "get_arq_pool", _get_pool)
    return pool

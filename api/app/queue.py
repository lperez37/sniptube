"""Shared arq Redis pool and enqueue helper for API routers."""

import logging

from arq.connections import ArqRedis, create_pool
from fastapi import HTTPException

from app.config import settings
from app.database import update_job
from app.worker import parse_redis_url

logger = logging.getLogger(__name__)

_pool: ArqRedis | None = None


async def get_arq_pool() -> ArqRedis:
    """Return the process-wide arq pool, creating it on first use."""
    global _pool
    if _pool is None:
        _pool = await create_pool(parse_redis_url(settings.redis_url))
    return _pool


async def close_arq_pool() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


async def enqueue_or_fail(func_name: str, job_id: str, *args) -> None:
    """Enqueue an arq job; on failure mark the DB job failed and raise 503."""
    try:
        pool = await get_arq_pool()
        await pool.enqueue_job(func_name, job_id, *args)
    except Exception as e:
        logger.error("Failed to enqueue %s for job %s: %s", func_name, job_id, e)
        await update_job(job_id, status="failed", error=f"Could not enqueue job: {e}")
        raise HTTPException(status_code=503, detail="Job queue unavailable, try again shortly")

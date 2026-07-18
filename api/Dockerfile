FROM python:3.12-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg curl unzip && \
    curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh && \
    apt-get purge -y curl unzip && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY pyproject.toml .
RUN pip install --no-cache-dir \
    "fastapi>=0.115.0" \
    "uvicorn[standard]>=0.30.0" \
    "arq>=0.26.0" \
    "aiosqlite>=0.20.0" \
    "yt-dlp>=2024.12.0" \
    "youtube-transcript-api>=1.0.0" \
    "redis>=5.0.0" \
    "pydantic>=2.9.0" \
    "pydantic-settings>=2.5.0" \
    "python-multipart>=0.0.12"

COPY app/ app/

# Run as a non-root user. UID 1000 matches the typical host user so the
# bind-mounted ./data volume stays writable from both sides.
RUN useradd --create-home --uid 1000 sniptube
USER sniptube

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

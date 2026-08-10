# Python 3.12: 3.13+ has no prebuilt wheels for some pinned dependencies, which turns a fast image
# build into a Rust toolchain compile.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Pillow needs these to decode JPEG, PNG and WebP.
RUN apt-get update && apt-get install -y --no-install-recommends \
      libjpeg62-turbo libpng16-16 libwebp7 \
    && rm -rf /var/lib/apt/lists/*

# Dependencies first so application edits don't invalidate the layer.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Run as a non-root user.
RUN useradd -r -u 1001 appuser && chown -R appuser /app
USER appuser

EXPOSE 8000

# Honour the platform's injected PORT (Render and Cloud Run both set it), defaulting for local runs.
# One worker: image work is CPU-bound, and extra workers on a small instance contend rather than
# help — scale by adding instances instead.
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1"]

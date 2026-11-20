FROM python:3.12-slim AS build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt ./
COPY vendor/telegram ./vendor/telegram
RUN pip wheel --no-cache-dir --wheel-dir /wheels -r requirements.txt

FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 DOCKER=1 HALCYON_NO_GIT=1 GIT_PYTHON_REFRESH=quiet
WORKDIR /app
RUN groupadd --system halcyon && useradd --system --gid halcyon halcyon \
    && install -d -m 700 -o halcyon -g halcyon /data
RUN --mount=type=bind,from=build,source=/wheels,target=/wheels \
    pip install --no-cache-dir --no-index /wheels/*.whl
COPY halcyon ./halcyon
COPY assets ./assets
COPY CHANGELOG.md ./
RUN chmod -R a+rX /app
USER halcyon
CMD ["python", "-m", "halcyon", "--no-git"]

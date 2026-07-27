FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim AS base

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

WORKDIR /workspace

COPY pyproject.toml uv.lock ./

FROM base AS runtime
RUN uv sync --frozen --no-dev

COPY server ./server

WORKDIR /workspace/server

EXPOSE 8000
CMD ["uv", "run", "--frozen", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

FROM base AS test
RUN uv sync --frozen
COPY server ./server
CMD ["uv", "run", "--frozen", "pytest"]

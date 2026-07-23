# Python Agent 服务镜像（FastAPI + LangGraph）。
# 只 COPY 源码与锁文件，依赖由 uv 安装；不在此处跑构建。
FROM python:3.12-slim
WORKDIR /app

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy

RUN apt-get update \
 && apt-get install -y --no-install-recommends tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
 && pip install --no-cache-dir uv

COPY pyproject.toml uv.lock ./
# --frozen 保证与 uv.lock 完全一致，可复现。
RUN uv sync --frozen --no-dev

COPY app ./app

# 直接走 uv 的 venv，不用 `uv run`，启动更快、healthcheck 里 python 更干净。
ENV PATH="/app/.venv/bin:$PATH"

EXPOSE 8001

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]

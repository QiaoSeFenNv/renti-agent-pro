# Python Agent 服务镜像（FastAPI + LangGraph）。
# 改用 pip 装依赖，避开 uv 并发下载在某些网络下卡死的问题。
FROM python:3.12-slim
WORKDIR /app

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_INDEX_URL=https://mirrors.tencentyun.com/pypi/simple \
    PIP_DEFAULT_TIMEOUT=120 \
    PIP_RETRIES=10

# 换清华 Debian 源：兼容旧 sources.list 与新 sources.list.d/debian.sources 两种格式
RUN set -eux; \
    if [ -f /etc/apt/sources.list ]; then \
      sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g; s|security.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list; \
    fi; \
    if [ -f /etc/apt/sources.list.d/debian.sources ]; then \
      sed -i 's|http://deb.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g; s|http://security.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g; s|https://deb.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g; s|https://security.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources; \
    fi; \
    true

RUN apt-get update \
 && apt-get install -y --no-install-recommends tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 直接 pip 装依赖（腾讯云内网源），--retries/--timeout 容错，卡了会重试或报错而非干等。
RUN pip install --no-cache-dir \
      "fastapi>=0.115" \
      "uvicorn>=0.32" \
      "httpx>=0.27" \
      "pydantic>=2.9" \
      "langchain>=0.3" \
      "langchain-openai>=0.2" \
      "langgraph>=0.2" \
      "python-dotenv>=1.0"

COPY app ./app

EXPOSE 8001

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]

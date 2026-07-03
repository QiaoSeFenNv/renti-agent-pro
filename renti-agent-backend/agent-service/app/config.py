"""Agent 服务配置：环境变量集中读取。

Spring Boot 在启动 agent 服务时通过环境变量传入 DeepSeek 凭据与回调地址，
本模块仅负责解析，不做业务判断。
"""

from __future__ import annotations

import os

from dotenv import load_dotenv

load_dotenv(".env.local")


def _clean(value: str | None) -> str:
    return (value or "").strip().strip('"').strip("'")


class Settings:
    """进程级配置快照。"""

    def __init__(self) -> None:
        self.deepseek_api_key: str = _clean(os.environ.get("DEEPSEEK_API_KEY"))
        self.deepseek_base_url: str = _clean(os.environ.get("DEEPSEEK_BASE_URL")) or "https://api.deepseek.com"
        self.deepseek_chat_model: str = _clean(os.environ.get("DEEPSEEK_CHAT_MODEL")) or "deepseek-chat"
        self.llm_timeout_seconds: float = float(_clean(os.environ.get("LLM_TIMEOUT_SECONDS")) or "60")
        # Spring Boot 后端（工具端点宿主）
        self.spring_base_url: str = _clean(os.environ.get("SPRING_BASE_URL")) or "http://127.0.0.1:8080"
        self.internal_token: str = _clean(os.environ.get("RENTI_INTERNAL_TOKEN")) or "renti-internal-dev-token"
        self.tool_timeout_seconds: float = float(_clean(os.environ.get("TOOL_TIMEOUT_SECONDS")) or "20")


settings = Settings()

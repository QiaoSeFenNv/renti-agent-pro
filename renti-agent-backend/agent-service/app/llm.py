"""DeepSeek LLM 访问层（LangChain ChatOpenAI 封装）。

未配置 API Key 或调用失败时由上层降级，本模块只负责：
- 提供带超时的 ChatOpenAI 实例；
- 提供容错的 JSON 输出解析（模型偶尔输出 ```json 围栏或前后缀文本）。
"""

from __future__ import annotations

import json
import re
from typing import Any

from langchain_core.messages import BaseMessage
from langchain_openai import ChatOpenAI

from app.config import settings


def llm_configured() -> bool:
    return bool(settings.deepseek_api_key and settings.deepseek_chat_model)


def get_chat_model(temperature: float = 0.2, json_mode: bool = False) -> ChatOpenAI:
    if not llm_configured():
        raise RuntimeError("DeepSeek API 未配置。")
    kwargs: dict[str, Any] = {}
    if json_mode:
        kwargs["response_format"] = {"type": "json_object"}
    return ChatOpenAI(
        base_url=settings.deepseek_base_url,
        api_key=settings.deepseek_api_key,
        model=settings.deepseek_chat_model,
        temperature=temperature,
        timeout=settings.llm_timeout_seconds,
        max_retries=1,
        model_kwargs=kwargs,
    )


def invoke_json(messages: list[Any], temperature: float = 0.2) -> dict[str, Any]:
    """调用 LLM 并强制解析为 JSON 对象，失败抛异常由上层降级。"""
    model = get_chat_model(temperature=temperature, json_mode=True)
    response = model.invoke(messages)
    return extract_json_object(message_text(response))


def message_text(message: BaseMessage) -> str:
    content = message.content
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            part.get("text", "") if isinstance(part, dict) else str(part) for part in content
        )
    return str(content or "")


def extract_json_object(content: str) -> dict[str, Any]:
    text = (content or "").strip()
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, flags=re.DOTALL)
    if fenced:
        text = fenced.group(1)
    elif not text.startswith("{"):
        start = text.find("{")
        end = text.rfind("}")
        if start < 0 or end <= start:
            raise ValueError("model did not return a JSON object")
        text = text[start : end + 1]
    value = json.loads(text)
    if not isinstance(value, dict):
        raise ValueError("model JSON is not an object")
    return value

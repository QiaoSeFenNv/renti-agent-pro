"""Renti Agent 服务入口（FastAPI）。

契约 §A：GET /health、POST /agent/rental-search、POST /agent/property-insight、
POST /agent/property-chat。任何未预期失败统一返回
{"ok": false, "code": "agent_error", "summary": ...}（HTTP 200），由 Java 桥接降级。
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request

from app.graphs.property_insight import run_property_chat, run_property_insight
from app.graphs.rental_search import run_rental_search

logger = logging.getLogger("renti.agent")
logging.basicConfig(level=logging.INFO)

app = FastAPI(title="Renti Agent Service", version="2.0.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "graph": "rental-search-v2"}


@app.post("/agent/rental-search")
async def rental_search(request: Request) -> dict[str, Any]:
    payload = await _json_body(request)
    try:
        return run_rental_search(payload)
    except Exception as exc:
        logger.exception("rental-search failed")
        return _agent_error(exc)


@app.post("/agent/property-insight")
async def property_insight(request: Request) -> dict[str, Any]:
    payload = await _json_body(request)
    try:
        return run_property_insight(payload)
    except Exception as exc:
        logger.exception("property-insight failed")
        return _agent_error(exc)


@app.post("/agent/property-chat")
async def property_chat(request: Request) -> dict[str, Any]:
    payload = await _json_body(request)
    try:
        return run_property_chat(payload)
    except Exception as exc:
        logger.exception("property-chat failed")
        return _agent_error(exc)


async def _json_body(request: Request) -> dict[str, Any]:
    try:
        payload = await request.json()
    except Exception:
        return {}
    return payload if isinstance(payload, dict) else {}


def _agent_error(exc: Exception) -> dict[str, Any]:
    return {
        "ok": False,
        "code": "agent_error",
        "summary": f"{exc.__class__.__name__}: {str(exc)[:200]}",
    }

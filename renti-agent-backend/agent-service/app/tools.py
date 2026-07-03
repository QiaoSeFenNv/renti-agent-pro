"""Spring Boot internal 工具端点封装（契约 §B）。

每个工具调用都会向调用方传入的 toolTrace 列表追加一条
{"tool": ..., "status": "ok"|"error", "summary": ...}。
工具失败不抛异常（除非显式要求），返回 None / 空列表由图节点决定降级路径。
"""

from __future__ import annotations

from typing import Any

import httpx

from app.config import settings


class ToolError(Exception):
    """Spring 工具端点调用失败。"""


def _post(path: str, payload: dict[str, Any]) -> dict[str, Any]:
    url = settings.spring_base_url.rstrip("/") + path
    headers = {"X-Internal-Token": settings.internal_token}
    try:
        response = httpx.post(url, json=payload, headers=headers, timeout=settings.tool_timeout_seconds)
        response.raise_for_status()
        data = response.json()
    except Exception as exc:
        raise ToolError(f"{exc.__class__.__name__}: {str(exc)[:160]}") from exc
    if not isinstance(data, dict):
        raise ToolError("工具端点返回了非对象 JSON。")
    return data


def _trace(trace: list[dict[str, str]], tool: str, status: str, summary: str) -> None:
    trace.append({"tool": tool, "status": status, "summary": str(summary or "")[:240]})


def parse_requirement(text: str, city: str, trace: list[dict[str, str]]) -> dict[str, Any] | None:
    try:
        data = _post("/internal/agent-tools/parse-requirement", {"text": text, "city": city})
        _trace(trace, "parse_requirement", "ok", f"规则解析完成：budgetMax={data.get('budgetMax')}, layout={data.get('layout')}")
        return data
    except ToolError as exc:
        _trace(trace, "parse_requirement", "error", f"规则解析失败：{exc}")
        return None


def geocode(location_text: str, city: str, trace: list[dict[str, str]]) -> dict[str, Any] | None:
    try:
        data = _post("/internal/agent-tools/geocode", {"locationText": location_text, "city": city})
    except ToolError as exc:
        _trace(trace, "geocode", "error", f"地理编码失败：{exc}")
        return None
    if not data.get("ok"):
        _trace(trace, "geocode", "error", f"地理编码未命中：{data.get('code') or 'geocode_failed'}")
        return None
    _trace(trace, "geocode", "ok", f"已解析“{location_text}”→({data.get('longitude')},{data.get('latitude')})")
    return data


def search_listings_sql(params: dict[str, Any], trace: list[dict[str, str]]) -> list[dict[str, Any]]:
    try:
        data = _post("/internal/agent-tools/search-listings-sql", params)
        listings = data.get("listings") if isinstance(data.get("listings"), list) else []
        _trace(trace, "search_listings_sql", "ok", f"SQL 检索命中 {len(listings)} 条（total={data.get('total')}）。")
        return [row for row in listings if isinstance(row, dict)]
    except ToolError as exc:
        _trace(trace, "search_listings_sql", "error", f"SQL 检索失败：{exc}")
        return []


def search_listings_vector(text: str, city: str, limit: int, trace: list[dict[str, str]]) -> list[dict[str, Any]]:
    try:
        data = _post("/internal/agent-tools/search-listings-vector", {"text": text, "city": city, "limit": limit})
        hits = data.get("hits") if isinstance(data.get("hits"), list) else []
        _trace(trace, "search_listings_vector", "ok", f"向量检索命中 {len(hits)} 条。")
        return [row for row in hits if isinstance(row, dict)]
    except ToolError as exc:
        _trace(trace, "search_listings_vector", "error", f"向量检索失败：{exc}")
        return []


def search_listings_graph(listing_ids: list[str], city: str, trace: list[dict[str, str]]) -> list[dict[str, Any]]:
    try:
        data = _post("/internal/agent-tools/search-listings-graph", {"listingIds": listing_ids, "city": city})
        related = data.get("related") if isinstance(data.get("related"), list) else []
        _trace(trace, "search_listings_graph", "ok", f"图谱补充 {len(related)} 条关系上下文。")
        return [row for row in related if isinstance(row, dict)]
    except ToolError as exc:
        _trace(trace, "search_listings_graph", "error", f"图谱查询失败（已跳过）：{exc}")
        return []


def listing_detail(listing_id: str, trace: list[dict[str, str]]) -> dict[str, Any] | None:
    try:
        data = _post("/internal/agent-tools/listing-detail", {"listingId": listing_id})
    except ToolError as exc:
        _trace(trace, "listing_detail", "error", f"房源详情读取失败：{exc}")
        return None
    if not data.get("ok"):
        _trace(trace, "listing_detail", "error", f"房源不存在：{listing_id}")
        return None
    _trace(trace, "listing_detail", "ok", f"已读取房源详情：{listing_id}")
    return data.get("listing") if isinstance(data.get("listing"), dict) else None

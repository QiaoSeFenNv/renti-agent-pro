"""租房搜索多步编排（LangGraph StateGraph，重构版核心）。

节点流：
    parse_intent → resolve_center →(条件)→ retrieve → fuse → enrich_graph
        → rank → summarize → END
    resolve_center 失败（有地点关键词但 geocode 未命中）→ needs_clarification → END
    retrieve 两路全部失败 → retrieval_failed（ok:false）

响应结构对齐契约 §A（旧 run_rental_search_agent_payload 的 camelCase 形态）。
"""

from __future__ import annotations

from functools import lru_cache
from typing import Any, TypedDict

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph import END, START, StateGraph

from app import tools
from app.config import settings
from app.llm import invoke_json, llm_configured, message_text, get_chat_model

AGENT_NAME = "rental-search-graph"
AGENT_VERSION = "2.0"
DEFAULT_CITY = "上海"
DEFAULT_RADIUS_METERS = 2000
SORT_CHOICES = {"score_desc", "price_asc", "price_desc", "area_asc", "area_desc"}


class RentalSearchState(TypedDict, total=False):
    # 请求输入
    userId: int
    query: str
    city: str
    source: str
    requestCenter: dict[str, Any] | None
    radiusMeters: int
    settings: dict[str, Any]
    # 中间产物
    intent: dict[str, Any]
    mode: str  # llm | rules
    center: dict[str, Any] | None
    candidates: list[dict[str, Any]]
    recommendations: list[dict[str, Any]]
    toolTrace: list[dict[str, str]]
    warnings: list[str]
    summary: str
    route: str  # ok | needs_clarification | retrieval_failed


# ---------------------------------------------------------------------------
# 节点实现
# ---------------------------------------------------------------------------


def _parse_intent_node(state: RentalSearchState) -> dict[str, Any]:
    trace = list(state.get("toolTrace") or [])
    warnings = list(state.get("warnings") or [])
    query = str(state.get("query") or "")
    city = str(state.get("city") or DEFAULT_CITY)

    intent: dict[str, Any] | None = None
    mode = "rules"
    if llm_configured() and query:
        try:
            parsed = invoke_json(
                [SystemMessage(content=_intent_system_prompt()), HumanMessage(content=_intent_user_prompt(query, city))],
                temperature=0.0,
            )
            intent = _normalize_intent(parsed, query, city)
            mode = "llm"
            trace.append({"tool": "parse_intent", "status": "ok", "summary": f"LLM 已解析结构化需求：地点={intent['locationKeyword'] or '未识别'}，预算={intent['budgetMax']}。"})
        except Exception as exc:
            warnings.append("LLM 意图解析失败，已回退规则解析。")
            trace.append({"tool": "parse_intent", "status": "error", "summary": f"LLM 解析失败：{exc.__class__.__name__}: {str(exc)[:120]}"})

    if intent is None:
        rules = tools.parse_requirement(query, city, trace) or {}
        intent = _normalize_intent(rules, query, city)
        intent["locationKeyword"] = intent["locationKeyword"] or _local_location_keyword(query)
        mode = "rules"
        if not rules:
            warnings.append("规则解析工具不可用，已按原始文本兜底。")

    return {"intent": intent, "mode": mode, "toolTrace": trace, "warnings": warnings}


def _resolve_center_node(state: RentalSearchState) -> dict[str, Any]:
    trace = list(state.get("toolTrace") or [])
    warnings = list(state.get("warnings") or [])
    intent = dict(state.get("intent") or {})
    city = str(intent.get("city") or state.get("city") or DEFAULT_CITY)

    request_center = state.get("requestCenter") if isinstance(state.get("requestCenter"), dict) else None
    if request_center and request_center.get("longitude") is not None and request_center.get("latitude") is not None:
        center = {
            "longitude": float(request_center["longitude"]),
            "latitude": float(request_center["latitude"]),
            "label": str(request_center.get("label") or intent.get("locationKeyword") or "指定位置"),
            "city": city,
            "coordinateSystem": "GCJ02",
            "source": "request",
        }
        trace.append({"tool": "resolve_center", "status": "ok", "summary": f"使用请求携带的中心点：{center['label']}。"})
        return {"center": center, "route": "ok", "toolTrace": trace, "warnings": warnings}

    keyword = str(intent.get("locationKeyword") or "")
    if keyword:
        geo = tools.geocode(keyword, city, trace)
        if geo is None:
            warnings.append(f"未能定位“{keyword}”，请补充更具体的小区、地标或商圈名称。")
            return {"center": None, "route": "needs_clarification", "toolTrace": trace, "warnings": warnings}
        center = {
            "longitude": float(geo.get("longitude") or 0),
            "latitude": float(geo.get("latitude") or 0),
            "label": str(geo.get("label") or keyword),
            "city": city,
            "coordinateSystem": "GCJ02",
            "source": str(geo.get("source") or "amap_geocode"),
        }
        return {"center": center, "route": "ok", "toolTrace": trace, "warnings": warnings}

    # 无中心点也无地点关键词：按城市全域检索
    warnings.append(f"未识别到具体地点，已按 {city} 全城条件检索。")
    trace.append({"tool": "resolve_center", "status": "ok", "summary": f"无地点关键词，按 {city} 全城检索。"})
    return {"center": None, "route": "ok", "toolTrace": trace, "warnings": warnings}


def _retrieve_node(state: RentalSearchState) -> dict[str, Any]:
    trace = list(state.get("toolTrace") or [])
    warnings = list(state.get("warnings") or [])
    intent = dict(state.get("intent") or {})
    center = state.get("center")
    city = str(intent.get("city") or state.get("city") or DEFAULT_CITY)
    radius = int(state.get("radiusMeters") or DEFAULT_RADIUS_METERS)

    sql_params: dict[str, Any] = {
        "city": city,
        "centerLongitude": center.get("longitude") if center else None,
        "centerLatitude": center.get("latitude") if center else None,
        "radiusMeters": radius if center else None,
        "budgetMax": intent.get("budgetMax"),
        "layout": intent.get("layout"),
        "rentType": intent.get("rentType"),
        "limit": 40,
    }
    sql_rows = tools.search_listings_sql(sql_params, trace)

    vector_text = str(state.get("query") or "") or " ".join(
        str(part) for part in [intent.get("locationKeyword"), intent.get("layout"), *list(intent.get("preferences") or [])] if part
    )
    vector_hits = tools.search_listings_vector(vector_text, city, 20, trace) if vector_text else []

    candidates = _fuse_candidates(sql_rows, vector_hits)
    if not candidates:
        sql_failed = any(item["tool"] == "search_listings_sql" and item["status"] == "error" for item in trace)
        vector_failed = any(item["tool"] == "search_listings_vector" and item["status"] == "error" for item in trace)
        if sql_failed and (vector_failed or not vector_hits):
            warnings.append("检索工具不可用，无法完成本次房源查询。")
            return {"candidates": [], "route": "retrieval_failed", "toolTrace": trace, "warnings": warnings}
    return {"candidates": candidates, "route": "ok", "toolTrace": trace, "warnings": warnings}


def _fuse_candidates(sql_rows: list[dict[str, Any]], vector_hits: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """RRF 融合：SQL 命中为主（保留距离字段），向量命中补充语义分。"""
    fused: dict[str, dict[str, Any]] = {}
    k = 60.0
    for rank, row in enumerate(sql_rows):
        listing_id = str(row.get("listingId") or row.get("id") or "")
        if not listing_id:
            continue
        fused[listing_id] = {
            "listing": dict(row),
            "sqlRank": rank,
            "fusedScore": 1.0 / (k + rank),
            "vectorScore": 0.0,
            "fromSql": True,
        }
    for rank, hit in enumerate(vector_hits):
        listing_id = str(hit.get("listingId") or "")
        if not listing_id:
            continue
        score = float(hit.get("score") or 0.0)
        if listing_id in fused:
            fused[listing_id]["fusedScore"] += 0.6 / (k + rank)
            fused[listing_id]["vectorScore"] = score
        else:
            payload = hit.get("payload") if isinstance(hit.get("payload"), dict) else dict(hit)
            listing = dict(payload)
            listing.setdefault("listingId", listing_id)
            fused[listing_id] = {
                "listing": listing,
                "sqlRank": None,
                "fusedScore": 0.6 / (k + rank),
                "vectorScore": score,
                "fromSql": False,
            }
    return sorted(fused.values(), key=lambda item: item["fusedScore"], reverse=True)


def _enrich_graph_node(state: RentalSearchState) -> dict[str, Any]:
    trace = list(state.get("toolTrace") or [])
    candidates = list(state.get("candidates") or [])
    city = str((state.get("intent") or {}).get("city") or state.get("city") or DEFAULT_CITY)
    if not candidates:
        return {"candidates": candidates, "toolTrace": trace}

    top_ids = [str(item["listing"].get("listingId") or item["listing"].get("id") or "") for item in candidates[:15]]
    related = tools.search_listings_graph([lid for lid in top_ids if lid], city, trace)
    relations_by_id = {
        str(row.get("listingId") or ""): row.get("relations") if isinstance(row.get("relations"), list) else []
        for row in related
    }
    for item in candidates:
        listing_id = str(item["listing"].get("listingId") or item["listing"].get("id") or "")
        item["graphRelations"] = relations_by_id.get(listing_id) or []
    return {"candidates": candidates, "toolTrace": trace}


def _rank_node(state: RentalSearchState) -> dict[str, Any]:
    trace = list(state.get("toolTrace") or [])
    intent = dict(state.get("intent") or {})
    center = state.get("center")
    radius = int(state.get("radiusMeters") or DEFAULT_RADIUS_METERS)
    user_settings = state.get("settings") if isinstance(state.get("settings"), dict) else {}
    limit = _bounded_int(user_settings.get("listingPageSize"), default=10, minimum=1, maximum=50)

    recommendations = [
        _score_candidate(item, intent, center, radius) for item in list(state.get("candidates") or [])
    ]
    sort = str(intent.get("sort") or user_settings.get("defaultSort") or "score_desc")
    recommendations = _sorted_recommendations(recommendations, sort)[:limit]
    trace.append({"tool": "rank", "status": "ok", "summary": f"启发式评分完成，产出 {len(recommendations)} 条推荐（sort={sort}）。"})
    return {"recommendations": recommendations, "toolTrace": trace}


def _score_candidate(
    item: dict[str, Any],
    intent: dict[str, Any],
    center: dict[str, Any] | None,
    radius: int,
) -> dict[str, Any]:
    listing = dict(item.get("listing") or {})
    reasons: list[str] = []
    risk_notes: list[str] = []
    score = 55.0

    price = _optional_int(listing.get("rentPrice"))
    budget = _optional_int(intent.get("budgetMax"))
    if price and budget:
        if price <= budget:
            score += 10 + min(6.0, (budget - price) / max(budget, 1) * 20)
            reasons.append(f"租金 {price} 元/月，在预算 {budget} 元内")
        else:
            over = (price - budget) / max(budget, 1)
            score -= min(20.0, over * 50)
            risk_notes.append(f"租金 {price} 元/月，超出预算 {budget} 元")
    elif price:
        reasons.append(f"租金 {price} 元/月")

    distance_m = _optional_int(listing.get("distanceM"))
    within_radius = distance_m is not None and distance_m <= radius
    if center is not None and distance_m is not None:
        if within_radius:
            score += 8 * (1 - distance_m / max(radius, 1))
            reasons.append(f"距目标点约 {distance_m} 米")
        else:
            score -= 6
            risk_notes.append(f"距目标点约 {distance_m} 米，超出检索半径")

    metro_distance = _optional_int(listing.get("metroDistanceM"))
    if metro_distance is not None and metro_distance > 0 and metro_distance <= 800:
        score += 6
        reasons.append(f"距地铁 {listing.get('nearestMetro') or ''} 约 {metro_distance} 米".strip())

    commute_limit = _optional_int(intent.get("commuteLimitMinutes"))
    commute_minutes = _optional_int(listing.get("commuteMinutes"))
    if commute_limit and commute_minutes:
        if commute_minutes <= commute_limit:
            score += 6
            reasons.append(f"通勤约 {commute_minutes} 分钟，满足 {commute_limit} 分钟要求")
        else:
            risk_notes.append(f"通勤约 {commute_minutes} 分钟，超出 {commute_limit} 分钟要求")

    layout = str(intent.get("layout") or "")
    if layout and layout in str(listing.get("layout") or ""):
        score += 5
        reasons.append(f"户型匹配 {listing.get('layout')}")
    rent_type = str(intent.get("rentType") or "")
    if rent_type and rent_type == str(listing.get("rentType") or ""):
        score += 4
        reasons.append(f"租赁方式匹配（{rent_type}）")

    tags = [str(tag) for tag in (listing.get("tags") or []) if tag]
    matched_tags = [pref for pref in (intent.get("preferences") or []) if any(str(pref) in tag or tag in str(pref) for tag in tags)]
    if matched_tags:
        score += min(6.0, len(matched_tags) * 2)
        reasons.append("偏好匹配：" + "、".join(str(tag) for tag in matched_tags[:3]))

    vector_score = float(item.get("vectorScore") or 0.0)
    if vector_score > 0:
        score += min(8.0, vector_score * 8)
        if vector_score >= 0.5:
            reasons.append("语义相关度高")

    if item.get("graphRelations"):
        score += 2
        reasons.append("图谱关联：同小区/相似房源可参考")

    risk_tags = [str(tag) for tag in (listing.get("riskTags") or []) if tag]
    if risk_tags:
        score -= min(9.0, len(risk_tags) * 3)
        risk_notes.extend(risk_tags[:3])

    score = max(0.0, min(100.0, score))
    return {
        **listing,
        "score": round(score, 1),
        "reasons": reasons[:6],
        "riskNotes": risk_notes[:5],
        "distanceM": distance_m,
        "withinRadius": within_radius,
        "match": "·".join(reasons[:2]) if reasons else "综合匹配",
    }


def _sorted_recommendations(rows: list[dict[str, Any]], sort: str) -> list[dict[str, Any]]:
    if sort == "price_asc":
        return sorted(rows, key=lambda row: _optional_int(row.get("rentPrice")) or 10**9)
    if sort == "price_desc":
        return sorted(rows, key=lambda row: _optional_int(row.get("rentPrice")) or -1, reverse=True)
    if sort == "area_asc":
        return sorted(rows, key=lambda row: _optional_int(row.get("areaSqm")) or 10**9)
    if sort == "area_desc":
        return sorted(rows, key=lambda row: _optional_int(row.get("areaSqm")) or -1, reverse=True)
    return sorted(rows, key=lambda row: float(row.get("score") or 0), reverse=True)


def _summarize_node(state: RentalSearchState) -> dict[str, Any]:
    trace = list(state.get("toolTrace") or [])
    intent = dict(state.get("intent") or {})
    recommendations = list(state.get("recommendations") or [])
    center = state.get("center")

    summary = _template_summary(intent, recommendations, center)
    if llm_configured() and recommendations:
        try:
            top = [
                {
                    "title": row.get("title"),
                    "community": row.get("community"),
                    "rentPrice": row.get("rentPrice"),
                    "layout": row.get("layout"),
                    "distanceM": row.get("distanceM"),
                    "reasons": row.get("reasons"),
                }
                for row in recommendations[:5]
            ]
            model = get_chat_model(temperature=0.4)
            response = model.invoke(
                [
                    SystemMessage(
                        content="你是租房推荐助手。基于给定的解析意图与推荐结果，用不超过 120 字的中文总结本次推荐："
                        "说明匹配了哪些条件、推荐几套、最值得看哪一套及原因。不得编造数据。只输出总结文本。"
                    ),
                    HumanMessage(content=str({"intent": intent, "recommendations": top})),
                ]
            )
            text = message_text(response).strip()
            if text:
                summary = text[:300]
                trace.append({"tool": "summarize", "status": "ok", "summary": "LLM 已生成自然语言总结。"})
        except Exception as exc:
            trace.append({"tool": "summarize", "status": "error", "summary": f"LLM 总结失败，使用模板文案：{exc.__class__.__name__}"})
    return {"summary": summary, "toolTrace": trace}


def _template_summary(intent: dict[str, Any], recommendations: list[dict[str, Any]], center: dict[str, Any] | None) -> str:
    city = str(intent.get("city") or DEFAULT_CITY)
    location = str((center or {}).get("label") or intent.get("locationKeyword") or "")
    parts = [f"已在 {city}" + (f"“{location}”附近" if location else "全城")]
    if intent.get("budgetMax"):
        parts.append(f"按预算 {intent['budgetMax']} 元以内")
    if intent.get("layout"):
        parts.append(f"户型 {intent['layout']}")
    if not recommendations:
        return "，".join(parts) + " 检索，未找到匹配房源，建议放宽预算或扩大范围。"
    return "，".join(parts) + f" 检索，共推荐 {len(recommendations)} 套房源，已按匹配度排序。"


def _needs_clarification_node(state: RentalSearchState) -> dict[str, Any]:
    intent = dict(state.get("intent") or {})
    missing = list(intent.get("missingFields") or [])
    if "locationKeyword" not in missing:
        missing.append("locationKeyword")
    intent["missingFields"] = missing
    keyword = str(intent.get("locationKeyword") or "")
    summary = (
        f"未能定位“{keyword}”，请补充更具体的小区、地标或商圈名称。"
        if keyword
        else "请补充目标地点（小区、地标或商圈），以便按位置推荐房源。"
    )
    return {"intent": intent, "summary": summary, "recommendations": []}


# ---------------------------------------------------------------------------
# 意图解析辅助
# ---------------------------------------------------------------------------


def _intent_system_prompt() -> str:
    return (
        "你是租房搜索的意图解析器，唯一任务是把中文租房需求解析成 JSON 对象。"
        "不要调用工具，不要解释过程，只输出一个 JSON 对象。"
        "字段：city, locationKeyword, budgetMax, areaMin, areaMax, layout, rentType, "
        "commuteLimitMinutes, sort, preferences, avoidances, confidence, missingFields。"
        "city 用中文城市名，用户未提城市时用传入的默认城市。"
        "locationKeyword 只保留小区、学校、商圈、地标、行政区等地点关键词，没有则为空字符串。"
        "budgetMax 是每月最高租金整数，没有为 null。areaMin/areaMax 是面积平方米整数，没有为 null。"
        "layout 规范为 1室1厅、2室1厅 等，没有为 null。rentType 可选 整租、合租，没有为 null。"
        "commuteLimitMinutes 是通勤上限分钟整数，没有为 null。"
        "sort 可选 score_desc、price_asc、price_desc、area_asc、area_desc，默认 score_desc。"
        "preferences 是偏好字符串数组（如 近地铁、安静、采光好、电梯）；avoidances 是要避开的条件数组。"
        "confidence 是 0 到 1 的数字。missingFields 是缺失的重要字段名数组（如缺少地点时包含 locationKeyword）。"
    )


def _intent_user_prompt(query: str, city: str) -> str:
    import json

    return json.dumps({"defaultCity": city, "text": query}, ensure_ascii=False)


def _normalize_intent(value: dict[str, Any], query: str, city: str) -> dict[str, Any]:
    sort = str(value.get("sort") or "").strip().lower()
    preferences = _string_list(value.get("preferences"))
    missing = _string_list(value.get("missingFields"))
    keyword = str(value.get("locationKeyword") or "").strip()[:80]
    if not keyword and "locationKeyword" not in missing:
        missing.append("locationKeyword")
    return {
        "city": str(value.get("city") or city or DEFAULT_CITY).strip()[:40] or DEFAULT_CITY,
        "locationKeyword": keyword,
        "budgetMax": _optional_int(value.get("budgetMax")),
        "areaMin": _optional_int(value.get("areaMin")),
        "areaMax": _optional_int(value.get("areaMax")),
        "layout": (str(value.get("layout")).strip()[:40] if value.get("layout") else None),
        "rentType": (str(value.get("rentType")).strip()[:20] if value.get("rentType") else None),
        "commuteLimitMinutes": _optional_int(value.get("commuteLimitMinutes")),
        "sort": sort if sort in SORT_CHOICES else "score_desc",
        "preferences": preferences[:8],
        "avoidances": _string_list(value.get("avoidances"))[:8],
        "confidence": max(0.0, min(1.0, _optional_float(value.get("confidence")) or 0.6)),
        "missingFields": missing[:8],
        "rawText": query,
    }


def _local_location_keyword(query: str) -> str:
    """规则模式下的轻量地点提取兜底（parse-requirement 工具不返回地点）。"""
    import re

    match = re.search(r"(.+?)(?:附近|周边|旁边|边上|周围)", query)
    if not match:
        return ""
    value = match.group(1)
    for marker in ("我想找", "想找", "帮我找", "帮我在", "找", "在", "靠近", "位于"):
        index = value.rfind(marker)
        if index >= 0:
            value = value[index + len(marker):]
            break
    value = re.sub(r"\d{3,6}\s*(?:元/月|元|块)?\s*(?:以内|以下|内)", " ", value)
    return " ".join(value.split()).strip(" ，,。.;；的")[:80]


# ---------------------------------------------------------------------------
# 图构建与入口
# ---------------------------------------------------------------------------


def _route_after_center(state: RentalSearchState) -> str:
    return "needs_clarification" if state.get("route") == "needs_clarification" else "retrieve"


def _route_after_retrieve(state: RentalSearchState) -> str:
    return "retrieval_failed" if state.get("route") == "retrieval_failed" else "fuse_done"


@lru_cache(maxsize=1)
def compiled_graph():
    builder = StateGraph(RentalSearchState)
    builder.add_node("parse_intent", _parse_intent_node)
    builder.add_node("resolve_center", _resolve_center_node)
    builder.add_node("retrieve", _retrieve_node)
    builder.add_node("enrich_graph", _enrich_graph_node)
    builder.add_node("rank", _rank_node)
    builder.add_node("summarize", _summarize_node)
    builder.add_node("needs_clarification", _needs_clarification_node)

    builder.add_edge(START, "parse_intent")
    builder.add_edge("parse_intent", "resolve_center")
    builder.add_conditional_edges(
        "resolve_center",
        _route_after_center,
        {"needs_clarification": "needs_clarification", "retrieve": "retrieve"},
    )
    builder.add_conditional_edges(
        "retrieve",
        _route_after_retrieve,
        {"retrieval_failed": END, "fuse_done": "enrich_graph"},
    )
    builder.add_edge("enrich_graph", "rank")
    builder.add_edge("rank", "summarize")
    builder.add_edge("summarize", END)
    builder.add_edge("needs_clarification", END)
    return builder.compile()


def run_rental_search(payload: dict[str, Any]) -> dict[str, Any]:
    """执行图并组装契约 §A 响应。"""
    center = payload.get("center") if isinstance(payload.get("center"), dict) else None
    initial: RentalSearchState = {
        "userId": int(payload.get("userId") or 0),
        "query": str(payload.get("query") or ""),
        "city": str(payload.get("city") or DEFAULT_CITY),
        "source": str(payload.get("source") or "text"),
        "requestCenter": center,
        "radiusMeters": _bounded_int(payload.get("radiusMeters"), default=DEFAULT_RADIUS_METERS, minimum=200, maximum=20000),
        "settings": payload.get("settings") if isinstance(payload.get("settings"), dict) else {},
        "toolTrace": [],
        "warnings": [],
    }
    final = compiled_graph().invoke(initial)

    route = str(final.get("route") or "ok")
    intent = dict(final.get("intent") or {})
    recommendations = list(final.get("recommendations") or [])
    trace = list(final.get("toolTrace") or [])
    warnings = list(final.get("warnings") or [])
    mode = str(final.get("mode") or "rules")

    if route == "retrieval_failed":
        return {
            "ok": False,
            "code": "retrieval_failed",
            "summary": "房源检索工具不可用，无法完成本次查询。",
            "toolTrace": trace,
            "warnings": warnings,
            "agent": _agent_block(mode, initial["userId"], intent),
        }

    resolved_center = final.get("center") if isinstance(final.get("center"), dict) else None
    return {
        "ok": True,
        "intent": "needs_clarification" if route == "needs_clarification" else "rent_search_nearby",
        "queryText": initial["query"],
        "parsed": {
            "city": intent.get("city") or initial["city"],
            "locationText": intent.get("locationKeyword") or "",
            "radiusMeters": initial["radiusMeters"],
            "sort": intent.get("sort") or "score_desc",
            "constraints": {
                "budgetMax": intent.get("budgetMax"),
                "areaMin": intent.get("areaMin"),
                "areaMax": intent.get("areaMax"),
                "layout": intent.get("layout"),
                "rentType": intent.get("rentType"),
                "commuteLimitMinutes": intent.get("commuteLimitMinutes"),
                "preferences": intent.get("preferences") or [],
                "avoidances": intent.get("avoidances") or [],
                "agentConfidence": intent.get("confidence"),
            },
        },
        "center": resolved_center,
        "radiusMeters": initial["radiusMeters"],
        "recommendations": recommendations,
        "markers": [
            {
                "id": str(row.get("listingId") or row.get("id") or ""),
                "title": str(row.get("title") or row.get("community") or ""),
                "longitude": row.get("longitude"),
                "latitude": row.get("latitude"),
                "rentPrice": row.get("rentPrice"),
            }
            for row in recommendations
            if row.get("longitude") is not None and row.get("latitude") is not None
        ],
        "toolTrace": trace,
        "summary": str(final.get("summary") or ""),
        "warnings": warnings,
        "empty": len(recommendations) == 0,
        "agent": _agent_block(mode, initial["userId"], intent),
    }


def _agent_block(mode: str, user_id: int, intent: dict[str, Any]) -> dict[str, Any]:
    return {
        "name": AGENT_NAME,
        "version": AGENT_VERSION,
        "mode": mode,
        "userId": user_id,
        "intent": intent,
    }


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------


def _optional_int(value: Any) -> int | None:
    if value in (None, ""):
        return None
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def _optional_float(value: Any) -> float | None:
    if value in (None, ""):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _bounded_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    parsed = _optional_int(value)
    if parsed is None:
        parsed = default
    return max(minimum, min(maximum, parsed))


def _string_list(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(item).strip()[:40] for item in value if str(item).strip()]
    if isinstance(value, str):
        return [item.strip()[:40] for item in value.replace("，", ",").replace("、", ",").split(",") if item.strip()]
    return []

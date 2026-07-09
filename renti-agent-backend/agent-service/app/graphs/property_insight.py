"""房源详情洞察 + 房源问答（重构版）。

- run_property_insight：listing 数据（Java 传入）+ 可选 graph/vector 工具补充 →
  LLM 生成结构化 JSON（valueIndex/environmentEvaluation/commuteEvaluation/insight/pros/cons）；
  LLM 不可用时降级规则计算（评分公式参考旧 property_insight_agent 的规则模式）。
- run_property_chat：带 history 的 grounding 对话，可调工具查相似/关联房源，回答带 citations。
"""

from __future__ import annotations

import json
import re
import time
from datetime import UTC, datetime
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from app import tools
from app.llm import extract_json_object, get_chat_model, invoke_json, llm_configured, message_text

AGENT_NAME = "PropertyInsightAgent"
AGENT_VERSION = "property-insight-v2"
MAX_CHAT_TOOL_ROUNDS = 3


# ---------------------------------------------------------------------------
# property-insight
# ---------------------------------------------------------------------------


def run_property_insight(payload: dict[str, Any]) -> dict[str, Any]:
    started = time.perf_counter()
    listing = payload.get("listing") if isinstance(payload.get("listing"), dict) else {}
    listing_id = str(payload.get("listingId") or listing.get("listingId") or listing.get("id") or "")
    focus = str(payload.get("focus") or "balanced")
    user_id = int(payload.get("userId") or 0)
    trace: list[dict[str, str]] = []

    if not listing and listing_id:
        listing = tools.listing_detail(listing_id, trace) or {}
    if not listing:
        return {"ok": False, "code": "not_found", "summary": "房源不存在或没有可分析的房源数据。", "toolTrace": trace}

    context = _insight_context(listing, listing_id, focus, trace)

    mode, status, provider, model, usage = "rules", "fallback", "local", "", {}
    insight: dict[str, Any] | None = None
    if llm_configured():
        try:
            parsed = invoke_json(
                [
                    SystemMessage(content=_insight_system_prompt()),
                    HumanMessage(content=json.dumps({"focus": focus, "context": context}, ensure_ascii=False)),
                ],
                temperature=0.2,
            )
            insight = _normalize_insight(parsed, listing)
            mode, status, provider = "llm", "success", "deepseek"
            from app.config import settings as app_settings

            model = app_settings.deepseek_chat_model
            trace.insert(0, {"tool": "property_insight_agent", "status": "success", "summary": "LLM 已生成房源结构化分析。"})
        except Exception as exc:
            trace.insert(0, {"tool": "property_insight_agent", "status": "fallback", "summary": f"LLM 分析失败，使用规则计算：{exc.__class__.__name__}: {str(exc)[:120]}"})
    else:
        trace.insert(0, {"tool": "property_insight_agent", "status": "not_configured", "summary": "DeepSeek 未配置，使用规则计算。"})

    if insight is None:
        insight = _rule_insight(listing)

    computed_at = datetime.now(UTC).isoformat()
    warnings = _string_list(insight.get("warnings"))[:8]
    detail_patch = {
        "insight": insight.get("insight") or insight.get("summary") or "",
        "pros": _string_list(insight.get("pros"))[:6],
        "cons": _string_list(insight.get("cons"))[:6],
        "score": _bounded_int(insight.get("score"), default=60, minimum=0, maximum=100),
        "valueIndex": {
            "score": _bounded_int(insight.get("score"), default=60, minimum=0, maximum=100),
            "summary": _clean(insight.get("scoreSummary"), default="已结合房租、居住环境、户型面积和配套完成评分。", limit=500),
            "factors": _factor_rows(insight.get("scoreFactors")),
            "evidence": _string_list(insight.get("scoreEvidence"))[:6],
        },
        "environmentEvaluation": {
            "score": _bounded_int(insight.get("environmentScore"), default=60, minimum=0, maximum=100),
            "summary": _clean(insight.get("environmentSummary"), default="已按周边生活环境和房源基础信息进行判断。", limit=500),
            "factors": _string_list(insight.get("environmentFactors"))[:6],
        },
        "commuteEvaluation": {
            "score": _bounded_int(insight.get("commuteScore"), default=60, minimum=0, maximum=100),
            "summary": _clean(insight.get("commuteSummary"), default="已结合地铁距离和通勤字段进行通勤判断。", limit=500),
            "factors": _string_list(insight.get("commuteFactors"))[:6],
            "routeNote": _clean(insight.get("commuteRouteNote"), default="地图展示为直线距离；路线距离和时长以后端高德距离服务为准。", limit=500),
        },
        "analysisMeta": {
            "status": _clean(insight.get("status"), default="ready", limit=20),
            "source": "llm" if mode == "llm" else "local_fallback",
            "computedAt": computed_at,
            "warnings": warnings,
            "model": model,
            "agentMode": mode,
        },
    }
    return {
        "ok": True,
        "listingId": listing_id,
        "cacheHit": False,
        "summary": insight.get("summary") or "房源分析已生成。",
        "analysis": {
            "status": detail_patch["analysisMeta"]["status"],
            "source": detail_patch["analysisMeta"]["source"],
            "computedAt": computed_at,
            "warnings": warnings,
            "insight": insight,
        },
        "detailPatch": detail_patch,
        "toolTrace": trace,
        "agent": {
            "name": AGENT_NAME,
            "version": AGENT_VERSION,
            "mode": mode,
            "status": status,
            "provider": provider,
            "model": model,
            "usage": usage,
            "durationMs": round((time.perf_counter() - started) * 1000),
            "userId": user_id,
        },
    }


def _insight_context(listing: dict[str, Any], listing_id: str, focus: str, trace: list[dict[str, str]]) -> dict[str, Any]:
    city = str(listing.get("city") or "上海")
    context: dict[str, Any] = {
        "listingId": listing_id,
        "listing": _public_listing(listing),
        "focus": focus,
    }
    if listing_id:
        related = tools.search_listings_graph([listing_id], city, trace)
        context["graphRelations"] = (related[0].get("relations") if related and isinstance(related[0].get("relations"), list) else [])[:8]
    similar_text = " ".join(str(part) for part in [listing.get("community"), listing.get("layout"), listing.get("businessArea")] if part)
    if similar_text:
        hits = tools.search_listings_vector(similar_text, city, 5, trace)
        context["similarListings"] = [
            {
                "listingId": hit.get("listingId"),
                "score": hit.get("score"),
                "title": (hit.get("payload") or {}).get("title") if isinstance(hit.get("payload"), dict) else hit.get("title"),
                "rentPrice": (hit.get("payload") or {}).get("rentPrice") if isinstance(hit.get("payload"), dict) else hit.get("rentPrice"),
            }
            for hit in hits
            if str(hit.get("listingId") or "") != listing_id
        ][:4]
    return context


def _public_listing(listing: dict[str, Any]) -> dict[str, Any]:
    return {
        "city": listing.get("city") or "",
        "district": listing.get("district") or "",
        "businessArea": listing.get("businessArea") or "",
        "community": listing.get("community") or "",
        "title": listing.get("title") or "",
        "rentPrice": listing.get("rentPrice"),
        "layout": listing.get("layout") or "",
        "areaSqm": listing.get("areaSqm"),
        "rentType": listing.get("rentType") or "",
        "nearestMetro": listing.get("nearestMetro") or "",
        "metroDistanceM": listing.get("metroDistanceM"),
        "commuteMinutes": listing.get("commuteMinutes"),
        "tags": _string_list(listing.get("tags"))[:10],
        "riskTags": _string_list(listing.get("riskTags"))[:6],
        "source": listing.get("source") or "",
        "sourceUrl": listing.get("sourceUrl") or "",
    }


def _insight_system_prompt() -> str:
    return (
        "你是 PropertyInsightAgent，负责单套租房房源详情分析。"
        "只能基于用户消息中的 context 输出，不得编造价格、联系方式、可租状态或来源。"
        "字段缺失时必须写明数据暂缺或需要核验，并降低相应评分。"
        "graphRelations 和 similarListings 只是补充证据，不能当作已核验事实。"
        "请只输出 JSON 对象，字段包含：status, summary, insight, score, scoreSummary, scoreFactors, scoreEvidence, "
        "environmentScore, environmentSummary, environmentFactors, commuteScore, commuteSummary, commuteFactors, "
        "commuteRouteNote, pros, cons, warnings。"
        "score/environmentScore/commuteScore 都是 0-100 整数，评分要客观。"
        "scoreFactors 是对象数组，每项包含 label 和 value。"
        "pros/cons/scoreEvidence/environmentFactors/commuteFactors/warnings 都是中文字符串数组。"
        "status 可选 ready 或 partial。"
    )


def _normalize_insight(value: dict[str, Any], listing: dict[str, Any]) -> dict[str, Any]:
    fallback = _rule_insight(listing)
    return {
        "status": _clean(value.get("status"), default=fallback["status"], limit=20),
        "summary": _clean(value.get("summary"), default=fallback["summary"], limit=500),
        "insight": _clean(value.get("insight"), default=fallback["insight"], limit=1000),
        "score": _bounded_int(value.get("score"), default=int(fallback["score"]), minimum=0, maximum=100),
        "scoreSummary": _clean(value.get("scoreSummary"), default=fallback["scoreSummary"], limit=500),
        "scoreFactors": _factor_rows(value.get("scoreFactors")) or fallback["scoreFactors"],
        "scoreEvidence": _string_list(value.get("scoreEvidence"))[:6] or fallback["scoreEvidence"],
        "environmentScore": _bounded_int(value.get("environmentScore"), default=int(fallback["environmentScore"]), minimum=0, maximum=100),
        "environmentSummary": _clean(value.get("environmentSummary"), default=fallback["environmentSummary"], limit=500),
        "environmentFactors": _string_list(value.get("environmentFactors"))[:6] or fallback["environmentFactors"],
        "commuteScore": _bounded_int(value.get("commuteScore"), default=int(fallback["commuteScore"]), minimum=0, maximum=100),
        "commuteSummary": _clean(value.get("commuteSummary"), default=fallback["commuteSummary"], limit=500),
        "commuteFactors": _string_list(value.get("commuteFactors"))[:6] or fallback["commuteFactors"],
        "commuteRouteNote": _clean(value.get("commuteRouteNote"), default=fallback["commuteRouteNote"], limit=500),
        "pros": _string_list(value.get("pros"))[:6] or fallback["pros"],
        "cons": _string_list(value.get("cons"))[:6] or fallback["cons"],
        "warnings": _string_list(value.get("warnings"))[:8],
    }


def _rule_insight(listing: dict[str, Any]) -> dict[str, Any]:
    """LLM 不可用时的规则评分（公式对齐旧 property_insight_agent 规则模式）。"""
    title = str(listing.get("title") or listing.get("community") or "当前房源")
    environment_score = _environment_score(listing)
    commute_score = _commute_score(listing)
    value_score = _value_score(listing, environment_score, commute_score)
    metro = str(listing.get("nearestMetro") or "附近地铁待核验")
    metro_distance = _optional_int(listing.get("metroDistanceM"))

    pros = [f"{title} 已接入房源详情分析。"]
    if listing.get("layout"):
        pros.append(f"户型 {listing.get('layout')}，面积约 {listing.get('areaSqm') or '待补'}㎡。")
    if metro_distance:
        pros.append(f"最近轨交 {metro}，约 {metro_distance} 米。")
    if listing.get("tags"):
        pros.append("标签：" + "、".join(_string_list(listing.get("tags"))[:3]) + "。")

    cons = _string_list(listing.get("riskTags"))[:4]
    if not listing.get("sourceUrl"):
        cons.append("缺少来源链接，签约前需要人工核验。")
    if not cons:
        cons.append("价格、图片、可租状态仍需以来源平台为准。")

    commute_minutes = _optional_int(listing.get("commuteMinutes"))
    return {
        "status": "partial",
        "summary": f"{title} 的分析已按规则生成，建议重点核验来源、通勤和合同细节。",
        "insight": " ".join(pros[:4]),
        "score": value_score,
        "scoreSummary": f"综合评分 {value_score} 分：居住环境 {environment_score} 分，通勤效率 {commute_score} 分；租金和面积按采集字段客观折算。",
        "scoreFactors": [
            {"label": "居住环境", "value": f"{environment_score} 分，参考配套标签、地铁距离和基础字段。"},
            {"label": "通勤效率", "value": f"{commute_score} 分，参考地铁距离和通勤分钟数。"},
            {"label": "租金面积", "value": f"{listing.get('rentPrice') or '租金待补'} 元/月 / {listing.get('areaSqm') or '面积待补'}㎡。"},
            {"label": "户型", "value": str(listing.get("layout") or "户型待补")},
        ],
        "scoreEvidence": [],
        "environmentScore": environment_score,
        "environmentSummary": f"环境评分 {environment_score} 分。标签 {len(_string_list(listing.get('tags')))} 个，最近轨交参考为 {metro}。",
        "environmentFactors": [
            f"房源标签：{('、'.join(_string_list(listing.get('tags'))[:4])) or '待补'}。",
            f"面积/户型：{listing.get('areaSqm') or '待补'}㎡ / {listing.get('layout') or '待补'}。",
        ],
        "commuteScore": commute_score,
        "commuteSummary": (
            f"通勤评分 {commute_score} 分。距地铁约 {metro_distance} 米" + (f"，通勤约 {commute_minutes} 分钟。" if commute_minutes else "。")
            if metro_distance
            else f"通勤评分 {commute_score} 分。地铁距离数据待补全。"
        ),
        "commuteFactors": [
            f"最近地铁：{metro}，约 {metro_distance or '待补'} 米。",
            f"通勤分钟数：{commute_minutes or '待补'}。",
        ],
        "commuteRouteNote": "地图展示为直线距离；路线距离和时长以后端高德距离服务为准。",
        "pros": pros[:5],
        "cons": cons[:5],
        "warnings": [],
    }


def _environment_score(listing: dict[str, Any]) -> int:
    score = 54
    tags = _string_list(listing.get("tags"))
    if tags:
        score += min(18, len(tags) * 3)
    metro_distance = _optional_int(listing.get("metroDistanceM"))
    if metro_distance and metro_distance <= 1000:
        score += 10
    area = _optional_int(listing.get("areaSqm"))
    if area and area >= 35:
        score += 4
    if listing.get("layout"):
        score += 4
    risk_tags = _string_list(listing.get("riskTags"))
    if risk_tags:
        score -= min(12, len(risk_tags) * 4)
    return max(20, min(95, score))


def _commute_score(listing: dict[str, Any]) -> int:
    score = 62
    duration = _optional_int(listing.get("commuteMinutes"))
    metro_distance = _optional_int(listing.get("metroDistanceM"))
    if duration:
        if duration <= 25:
            score += 20
        elif duration <= 45:
            score += 8
        elif duration >= 75:
            score -= 18
        else:
            score -= 6
    if metro_distance:
        if metro_distance <= 800:
            score += 10
        elif metro_distance >= 1800:
            score -= 8
    return max(20, min(96, score))


def _value_score(listing: dict[str, Any], environment_score: int, commute_score: int) -> int:
    base = round(environment_score * 0.42 + commute_score * 0.34 + 55 * 0.24)
    price = _optional_int(listing.get("rentPrice"))
    area = _optional_int(listing.get("areaSqm"))
    if price and area:
        unit_price = price / max(area, 1)
        if unit_price <= 120:
            base += 10
        elif unit_price <= 180:
            base += 4
        elif unit_price >= 260:
            base -= 12
    elif price:
        if price <= 4500:
            base += 5
        elif price >= 9000:
            base -= 8
    if listing.get("layout"):
        base += 3
    return max(20, min(96, base))


# ---------------------------------------------------------------------------
# property-chat
# ---------------------------------------------------------------------------


def _strip_markdown(text: str) -> str:
    """聊天气泡按纯文本渲染：去掉标题/加粗/行内代码等 Markdown 排版符号。"""
    value = str(text or "")
    value = re.sub(r"^#{1,6}\s*", "", value, flags=re.MULTILINE)
    value = re.sub(r"\*\*([^*]+)\*\*", lambda m: m.group(1), value)
    value = re.sub(r"(?<!\*)\*([^*\n]+)\*(?!\*)", lambda m: m.group(1), value)
    value = value.replace("`", "")
    return value.strip()


def run_property_chat(payload: dict[str, Any]) -> dict[str, Any]:
    listing = payload.get("listing") if isinstance(payload.get("listing"), dict) else {}
    listing_id = str(payload.get("listingId") or listing.get("listingId") or listing.get("id") or "")
    question = str(payload.get("message") or "").strip()
    history = payload.get("history") if isinstance(payload.get("history"), list) else []
    trace: list[dict[str, str]] = []

    if not question:
        return {"ok": False, "code": "message_required", "summary": "请输入要询问的问题。"}
    if not listing and listing_id:
        listing = tools.listing_detail(listing_id, trace) or {}

    if llm_configured():
        try:
            return _chat_with_llm(question, listing, listing_id, history, trace)
        except Exception as exc:
            trace.append({"tool": "property_chat_agent", "status": "fallback", "summary": f"LLM 问答失败，使用本地回答：{exc.__class__.__name__}: {str(exc)[:120]}"})
    else:
        trace.append({"tool": "property_chat_agent", "status": "not_configured", "summary": "DeepSeek 未配置，使用本地回答。"})
    return _fallback_chat_answer(question, listing, trace)


def _chat_with_llm(
    question: str,
    listing: dict[str, Any],
    listing_id: str,
    history: list[dict[str, Any]],
    trace: list[dict[str, str]],
) -> dict[str, Any]:
    city = str(listing.get("city") or "上海")
    tool_specs = [
        {
            "type": "function",
            "function": {
                "name": "search_similar_listings",
                "description": "按自然语言描述在同城语义检索相似的已发布房源，用于回答“有没有更便宜/类似的选择”这类问题。",
                "parameters": {
                    "type": "object",
                    "properties": {"text": {"type": "string", "description": "检索描述，如小区名+户型+偏好"}},
                    "required": ["text"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "related_listings",
                "description": "查询当前房源在图谱中的关联房源（如同小区、同商圈），用于回答周边/同小区对比问题。",
                "parameters": {"type": "object", "properties": {}},
            },
        },
    ]

    messages: list[Any] = [SystemMessage(content=_chat_system_prompt(listing, listing_id))]
    for item in history[-8:]:
        role = str((item or {}).get("role") or "")
        content = str((item or {}).get("content") or "").strip()
        if not content:
            continue
        messages.append(HumanMessage(content=content) if role == "user" else AIMessage(content=content))
    messages.append(HumanMessage(content=question))

    model = get_chat_model(temperature=0.3).bind_tools(tool_specs)
    response: AIMessage | None = None
    for _ in range(MAX_CHAT_TOOL_ROUNDS):
        response = model.invoke(messages)
        calls = list(getattr(response, "tool_calls", None) or [])
        if not calls:
            break
        messages.append(response)
        for call in calls:
            name = str(call.get("name") or "")
            args = call.get("args") if isinstance(call.get("args"), dict) else {}
            if name == "search_similar_listings":
                hits = tools.search_listings_vector(str(args.get("text") or question), city, 5, trace)
                result = json.dumps({"hits": hits[:5]}, ensure_ascii=False, default=str)
            elif name == "related_listings":
                related = tools.search_listings_graph([listing_id] if listing_id else [], city, trace)
                result = json.dumps({"related": related[:5]}, ensure_ascii=False, default=str)
            else:
                result = json.dumps({"error": f"unknown tool {name}"}, ensure_ascii=False)
            messages.append(ToolMessage(content=result[:6000], tool_call_id=str(call.get("id") or name)))
    if response is None:
        raise RuntimeError("LLM 未返回任何内容。")

    content = message_text(response).strip()
    answer, citations = content, []
    try:
        parsed = extract_json_object(content)
        answer = _clean(parsed.get("answer") or parsed.get("content"), default=content, limit=5000)
        citations = _citation_rows(parsed.get("citations"))
    except Exception:
        pass
    answer = _strip_markdown(answer)
    if not answer:
        raise ValueError("empty answer")
    if not citations:
        citations = _default_citations(listing)
    trace.insert(0, {"tool": "property_chat_agent", "status": "success", "summary": "DeepSeek 已基于房源上下文回答问题。"})
    return {"ok": True, "content": answer, "citations": citations, "toolTrace": trace}


def _chat_system_prompt(listing: dict[str, Any], listing_id: str) -> str:
    context = json.dumps({"listingId": listing_id, "listing": _public_listing(listing)}, ensure_ascii=False)
    return (
        "你是 RentAI 的房源详情问答助手，定位是租房客户顾问：帮助用户判断是否值得看房、要问房东什么、风险在哪里。"
        "你只能根据下方房源上下文、对话历史和工具返回结果回答；"
        "禁止编造联系方式、可租状态、价格承诺或数据库外事实；字段缺失要说明待核验。"
        "需要对比相似或同小区房源时可调用提供的工具。"
        "answer 必须是纯文本短段落（可用「·」列点），不要使用 Markdown 表格/标题/加粗等排版符号，前端以纯文本气泡展示。"
        "最终回答请只输出 JSON 对象：{\"answer\": \"回答文本\", \"citations\": [{\"label\": \"依据名\", \"value\": \"依据内容\"}]}。"
        f"\n房源上下文：{context}"
    )


def _fallback_chat_answer(question: str, listing: dict[str, Any], trace: list[dict[str, str]]) -> dict[str, Any]:
    import re

    normalized = question.lower()
    title = str(listing.get("title") or listing.get("community") or "当前房源")
    price = listing.get("rentPrice") or "价格待补充"
    metro = str(listing.get("nearestMetro") or "待补全")
    metro_distance = _optional_int(listing.get("metroDistanceM"))

    if re.search(r"通勤|距离|地铁|交通|commute|metro", normalized):
        answer = (
            f"{title} 最近轨交为 {metro}" + (f"，约 {metro_distance} 米。" if metro_distance else "，距离数据待补全。")
            + "路线距离和时长以后端高德距离服务为准。"
        )
    elif re.search(r"价格|租金|预算|贵|便宜|price|rent", normalized):
        answer = f"当前记录中的租金为 {price} 元/月，来源 {listing.get('source') or '采集数据'}，建议核验最新价格、押金和付款周期。"
    elif re.search(r"风险|缺点|注意|坑|合同|押金|吵|噪|risk", normalized):
        risks = _string_list(listing.get("riskTags"))
        answer = f"需要重点关注：{'；'.join(risks[:5]) if risks else '来源真实性、可租状态、押金付款周期、图片是否实拍'}。请以来源平台和线下看房为准。"
    else:
        answer = (
            f"基于当前房源信息，建议先核验：租金 {price} 是否仍有效、户型 {listing.get('layout') or '待补'} 是否符合需求、"
            "来源链接和可租状态是否真实。你也可以继续问我通勤、价格或风险。"
        )

    trace.insert(0, {"tool": "property_chat_agent", "status": "local_fallback", "summary": "使用本地规则基于房源上下文回答。"})
    return {"ok": True, "content": answer, "citations": _default_citations(listing), "toolTrace": trace}


def _default_citations(listing: dict[str, Any]) -> list[dict[str, str]]:
    return [
        {"label": "租金", "value": str(listing.get("rentPrice") or "待补充")},
        {"label": "户型", "value": str(listing.get("layout") or "待补充")},
        {"label": "最近地铁", "value": str(listing.get("nearestMetro") or "待补充")},
        {"label": "数据来源", "value": str(listing.get("source") or listing.get("provider") or "待补充")},
    ]


def _citation_rows(value: Any) -> list[dict[str, str]]:
    rows = []
    if isinstance(value, list):
        for item in value:
            if not isinstance(item, dict):
                continue
            label = _clean(item.get("label"), limit=80)
            citation_value = _clean(item.get("value"), limit=260)
            if label and citation_value:
                rows.append({"label": label, "value": citation_value})
    return rows[:6]


def _factor_rows(value: Any) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    if isinstance(value, list):
        for item in value:
            if isinstance(item, dict):
                label = _clean(item.get("label") or item.get("name"), limit=80)
                text = _clean(item.get("value") or item.get("summary"), limit=180)
                if label and text:
                    rows.append({"label": label, "value": text})
            else:
                text = _clean(item, limit=180)
                if text:
                    rows.append({"label": "评分依据", "value": text})
    return rows[:6]


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------


def _clean(value: Any, default: str = "", limit: int = 200) -> str:
    cleaned = " ".join(str(value or "").strip().split()) or default
    return cleaned[:limit]


def _string_list(value: Any) -> list[str]:
    import re

    if isinstance(value, list):
        return [_clean(item, limit=160) for item in value if _clean(item, limit=160)]
    if isinstance(value, str):
        return [_clean(item, limit=160) for item in re.split(r"[,，、;；\n]+", value) if item.strip()]
    return []


def _optional_int(value: Any) -> int | None:
    if value in (None, ""):
        return None
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def _bounded_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    parsed = _optional_int(value)
    if parsed is None:
        parsed = default
    return max(minimum, min(maximum, parsed))

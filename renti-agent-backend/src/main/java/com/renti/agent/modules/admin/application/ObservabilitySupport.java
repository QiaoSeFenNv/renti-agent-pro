package com.renti.agent.modules.admin.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 观测记录公共工具：脱敏、截断、分页边界与命中提取。
 * 行为对齐旧 user_interactions.py / retrieval_audit.py / agent_trace.py 的同名私有函数。
 */
public final class ObservabilitySupport {

    /** 递归脱敏时整值替换为 [redacted] 的键（归一化为小写下划线后比较） */
    public static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "cookie", "password", "token", "session", "session_token",
            "api_key", "apikey", "deepseek_api_key", "embedding_api_key", "amap_web_service_key");

    private ObservabilitySupport() {
    }

    /** 对齐旧 _sanitize_event：dict 递归脱敏、list 截断 100、字符串截断 5000 */
    public static Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                var key = String.valueOf(entry.getKey());
                var normalized = key.replace("-", "_").toLowerCase();
                result.put(key, isSensitiveKey(normalized) ? "[redacted]" : sanitize(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>();
            for (var item : list.subList(0, Math.min(list.size(), 100))) {
                result.add(sanitize(item));
            }
            return result;
        }
        if (value instanceof String text) {
            return truncate(text, 5000);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitizeMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : (Map<String, Object>) sanitize(value);
    }

    public static boolean isSensitiveKey(String normalizedKey) {
        return SENSITIVE_KEYS.contains(normalizedKey)
                || normalizedKey.endsWith("_token")
                || normalizedKey.endsWith("_password")
                || normalizedKey.endsWith("_secret");
    }

    /** 移除黑名单键（浅层，对齐旧 _safe_request_payload） */
    public static Map<String, Object> stripBlockedKeys(Map<String, Object> payload, Set<String> blocked) {
        var result = new LinkedHashMap<String, Object>();
        if (payload == null) {
            return result;
        }
        for (var entry : payload.entrySet()) {
            var normalized = String.valueOf(entry.getKey()).replace("-", "_").toLowerCase();
            if (!blocked.contains(normalized)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() > limit ? value.substring(0, limit) : value;
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 对齐旧 _clean_text：合并空白并截断 */
    public static String cleanText(Object value, int limit) {
        var text = str(value).strip().replaceAll("\\s+", " ");
        return truncate(text, limit);
    }

    public static int boundedInt(Object value, int fallback, int minimum, int maximum) {
        var parsed = optionalInt(value);
        var result = parsed == null ? fallback : parsed;
        return Math.max(minimum, Math.min(maximum, result));
    }

    public static Integer optionalInt(Object value) {
        var parsed = optionalLong(value);
        return parsed == null ? null : parsed.intValue();
    }

    public static Long optionalLong(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return (long) Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : new ArrayList<>();
    }

    /** 对齐旧 _hits_from_response：从 recommendations/matches 提取命中摘要 */
    public static List<Object> hitsFromResponse(Map<String, Object> response) {
        var source = response == null ? Map.<String, Object>of() : response;
        var rows = source.get("recommendations") instanceof List<?> list ? list
                : source.get("matches") instanceof List<?> matches ? matches : List.of();
        var hits = new ArrayList<Object>();
        int rank = 0;
        for (var item : rows.subList(0, Math.min(rows.size(), 100))) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            var row = asMap(item);
            rank++;
            var hit = new LinkedHashMap<String, Object>();
            hit.put("rank", rank);
            hit.put("id", row.get("id") != null ? row.get("id") : row.get("listingId"));
            hit.put("title", row.get("title") != null ? row.get("title") : row.get("community"));
            hit.put("community", row.get("community"));
            hit.put("rentPrice", row.get("rent_price") != null ? row.get("rent_price") : row.get("rentPrice"));
            hit.put("score", row.get("score"));
            hit.put("semanticScore", row.get("semantic_score") != null ? row.get("semantic_score") : row.get("score"));
            hit.put("graphScore", row.get("graph_score"));
            hit.put("distanceM", row.get("distance_m"));
            hit.put("reasons", row.get("reasons") instanceof List<?> reasons ? reasons : List.of());
            hits.add(hit);
        }
        return hits;
    }

    /** 对齐旧 _total_hits / _result_count */
    public static int totalHits(Map<String, Object> response) {
        var source = response == null ? Map.<String, Object>of() : response;
        if (source.get("total") != null) {
            var parsed = optionalInt(source.get("total"));
            return parsed == null ? 0 : parsed;
        }
        var rows = source.get("recommendations") instanceof List<?> list ? list
                : source.get("matches") instanceof List<?> matches ? matches : null;
        return rows == null ? 0 : rows.size();
    }

    public static int totalPages(long total, int pageSize) {
        return (int) Math.max(1, (total + pageSize - 1) / pageSize);
    }
}

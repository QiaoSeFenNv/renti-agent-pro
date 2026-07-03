package com.renti.agent.modules.rag.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.infrastructure.client.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.boundedInt;

/**
 * LLM 候选复排：提示词与融合规则对齐旧 rag/reranker.py
 * （rerank_listing_candidates_payload / apply_rerank_result）。失败降级跳过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmRerankService {

    private final RagConfigService ragConfigService;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    /**
     * 对候选做 LLM 复排（给 search 模块用）。
     *
     * @param requirement 需求摘要（city/budgetMax/layout/preferences/avoidances）
     * @param candidates  候选列表（含 id/title/score_breakdown 等，snake_case 或 camelCase 均可）
     * @return {ok, items[{listingId,rank,score,rationale}], model, usage, summary}
     *         或 {ok:false, skipped:true, code, summary}
     */
    public Map<String, Object> rerankPayload(Map<String, Object> requirement, List<Map<String, Object>> candidates) {
        var rag = ragConfigService.effectiveRag();
        if (!Boolean.TRUE.equals(rag.get("llmRerankEnabled"))) {
            return skipped("disabled", "LLM rerank 未启用。");
        }
        if (!deepSeekClient.isConfigured()) {
            return skipped("not_configured", "LLM rerank 模型未配置。");
        }
        int topN = boundedInt(rag.get("llmRerankTopN"), 12, 3, 30);
        var source = candidates == null ? List.<Map<String, Object>>of() : candidates;
        var selected = source.subList(0, Math.min(Math.max(1, topN), source.size()));
        if (selected.size() < 2) {
            return skipped("not_enough_candidates", "候选不足，无需 LLM rerank。");
        }
        try {
            var content = deepSeekClient.chat(List.of(
                    DeepSeekClient.ChatMessage.system(systemPrompt()),
                    DeepSeekClient.ChatMessage.user(userPrompt(requirement, selected))), 0);
            var parsed = extractJsonObject(content);
            var items = itemsFromModel(parsed, selected);
            if (items.isEmpty()) {
                throw new IllegalStateException("empty rerank items");
            }
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("items", items);
            body.put("model", "deepseek-chat");
            body.put("usage", Map.of());
            body.put("summary", "LLM rerank 已重排 %d 套候选。".formatted(items.size()));
            return body;
        } catch (Exception exception) {
            log.debug("LLM rerank failed: {}", exception.getMessage());
            return skipped("rerank_failed", "LLM rerank 失败：" + exception.getClass().getSimpleName());
        }
    }

    /** 对齐旧 apply_rerank_result：融合 llm 微调分并按 rank/score 重排 */
    public List<Map<String, Object>> applyRerank(List<Map<String, Object>> candidates,
                                                 Map<String, Object> rerankResult) {
        if (rerankResult == null || !Boolean.TRUE.equals(rerankResult.get("ok"))) {
            return candidates;
        }
        var byId = new LinkedHashMap<String, Map<String, Object>>();
        if (rerankResult.get("items") instanceof List<?> items) {
            for (var item : items) {
                if (item instanceof Map<?, ?>) {
                    var row = asMap(item);
                    var listingId = str(row.get("listingId"));
                    if (!listingId.isEmpty()) {
                        byId.put(listingId, row);
                    }
                }
            }
        }
        if (byId.isEmpty()) {
            return candidates;
        }

        var updated = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (var candidate : candidates) {
            var listingId = firstNonEmpty(str(candidate.get("id")), str(candidate.get("listingId")));
            var llmItem = byId.get(listingId);
            index++;
            if (llmItem == null) {
                updated.add(candidate);
                continue;
            }
            double llmScore = boundedDouble(llmItem.get("score"), 0.0, -8.0, 8.0);
            var breakdown = new LinkedHashMap<>(asMap(candidate.get("score_breakdown")));
            breakdown.put("llm", round2(llmScore));
            double baseTotal = numberOf(breakdown.get("total"), numberOf(candidate.get("score"), 0.0));
            breakdown.put("total", round2(baseTotal + llmScore));
            var reasons = new ArrayList<Object>(candidate.get("reasons") instanceof List<?> list ? list : List.of());
            var rationale = str(llmItem.get("rationale")).strip();
            if (!rationale.isEmpty()) {
                reasons.add("LLM 复排：" + (rationale.length() > 120 ? rationale.substring(0, 120) : rationale));
            }
            var merged = new LinkedHashMap<>(candidate);
            merged.put("score", breakdown.get("total"));
            merged.put("score_breakdown", breakdown);
            merged.put("llm_rerank_score", round2(llmScore));
            var rank = optionalInt(llmItem.get("rank"));
            merged.put("llm_rerank_rank", rank == null ? index : rank);
            merged.put("reasons", reasons);
            updated.add(merged);
        }
        updated.sort((left, right) -> {
            int leftRank = rankOf(left);
            int rightRank = rankOf(right);
            if (leftRank != rightRank) {
                return Integer.compare(leftRank, rightRank);
            }
            int byScore = Double.compare(numberOf(right.get("score"), 0.0), numberOf(left.get("score"), 0.0));
            if (byScore != 0) {
                return byScore;
            }
            int byDistance = Double.compare(numberOf(left.get("distance_m"), 0.0), numberOf(right.get("distance_m"), 0.0));
            if (byDistance != 0) {
                return byDistance;
            }
            return Double.compare(numberOf(left.get("rent_price"), 0.0), numberOf(right.get("rent_price"), 0.0));
        });
        return updated;
    }

    // ------------------------------------------------------------------ 提示词（对齐旧 reranker.py）

    private String systemPrompt() {
        return """
                你是租房候选房源 reranker。只允许在给定候选中排序，不得编造新房源。\
                硬约束优先：城市、预算、户型、风险、可解释证据。\
                根据 SQL 分、语义分、图谱分和用户需求，输出 JSON 对象。\
                格式：{"items":[{"listingId":"...","rank":1,"score":2.5,"rationale":"..."}]}。\
                score 是 -8 到 8 的微调分；rationale 用一句中文说明。""";
    }

    private String userPrompt(Map<String, Object> requirement, List<Map<String, Object>> candidates) throws Exception {
        var req = requirement == null ? Map.<String, Object>of() : requirement;
        var payload = new LinkedHashMap<String, Object>();
        var requirementOut = new LinkedHashMap<String, Object>();
        requirementOut.put("city", req.get("city"));
        requirementOut.put("budgetMax", req.get("budgetMax") != null ? req.get("budgetMax") : req.get("budget_max"));
        requirementOut.put("layout", req.get("layout"));
        requirementOut.put("preferences", req.get("preferences") == null ? List.of() : req.get("preferences"));
        requirementOut.put("avoidances", req.get("avoidances") == null ? List.of() : req.get("avoidances"));
        payload.put("requirement", requirementOut);
        payload.put("candidates", candidates.stream().map(this::candidateForPrompt).toList());
        return objectMapper.writeValueAsString(payload);
    }

    /** 对齐旧 _candidate_for_prompt */
    private Map<String, Object> candidateForPrompt(Map<String, Object> item) {
        var breakdown = asMap(item.get("score_breakdown"));
        var candidate = new LinkedHashMap<String, Object>();
        candidate.put("listingId", item.get("id") != null ? item.get("id") : item.get("listingId"));
        candidate.put("title", item.get("title"));
        candidate.put("community", item.get("community"));
        candidate.put("district", item.get("district"));
        candidate.put("businessArea", item.get("business_area") != null
                ? item.get("business_area") : item.get("businessArea"));
        candidate.put("rentPrice", item.get("rent_price") != null ? item.get("rent_price") : item.get("rentPrice"));
        candidate.put("layout", item.get("layout"));
        candidate.put("areaSqm", item.get("area_sqm") != null ? item.get("area_sqm") : item.get("areaSqm"));
        candidate.put("metroDistanceM", item.get("metro_distance_m") != null
                ? item.get("metro_distance_m") : item.get("metroDistanceM"));
        candidate.put("tags", limited(item.get("tags"), 8));
        candidate.put("riskTags", limited(item.get("risk_tags") != null
                ? item.get("risk_tags") : item.get("riskTags"), 8));
        var scoreBreakdown = new LinkedHashMap<String, Object>();
        scoreBreakdown.put("base", breakdown.get("base"));
        scoreBreakdown.put("semantic", breakdown.get("semantic"));
        scoreBreakdown.put("graph", breakdown.get("graph"));
        scoreBreakdown.put("total", breakdown.get("total"));
        candidate.put("scoreBreakdown", scoreBreakdown);
        candidate.put("reasons", limited(item.get("reasons"), 6));
        return candidate;
    }

    /** 对齐旧 _items_from_model：只保留候选内、去重的条目 */
    private List<Map<String, Object>> itemsFromModel(Map<String, Object> parsed,
                                                     List<Map<String, Object>> candidates) {
        var candidateIds = new LinkedHashSet<String>();
        for (var item : candidates) {
            candidateIds.add(firstNonEmpty(str(item.get("id")), str(item.get("listingId"))));
        }
        var result = new ArrayList<Map<String, Object>>();
        var seen = new LinkedHashSet<String>();
        if (!(parsed.get("items") instanceof List<?> rawItems)) {
            return result;
        }
        int index = 0;
        for (var raw : rawItems) {
            index++;
            if (!(raw instanceof Map<?, ?>)) {
                continue;
            }
            var item = asMap(raw);
            var listingId = firstNonEmpty(str(item.get("listingId")), str(item.get("listing_id")),
                    str(item.get("id"))).strip();
            if (!candidateIds.contains(listingId) || !seen.add(listingId)) {
                continue;
            }
            var rationale = firstNonEmpty(str(item.get("rationale")), str(item.get("reason"))).strip();
            var entry = new LinkedHashMap<String, Object>();
            entry.put("listingId", listingId);
            var rank = optionalInt(item.get("rank"));
            entry.put("rank", rank == null ? index : rank);
            entry.put("score", boundedDouble(item.get("score"), 0.0, -8.0, 8.0));
            entry.put("rationale", rationale.length() > 160 ? rationale.substring(0, 160) : rationale);
            result.add(entry);
        }
        return result;
    }

    private Map<String, Object> extractJsonObject(String content) throws Exception {
        var text = content == null ? "" : content.strip();
        var fenced = java.util.regex.Pattern
                .compile("```(?:json)?\\s*(\\{.*?})\\s*```", java.util.regex.Pattern.DOTALL)
                .matcher(text);
        if (fenced.find()) {
            text = fenced.group(1);
        } else if (!text.startsWith("{")) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalStateException("model did not return a JSON object");
            }
            text = text.substring(start, end + 1);
        }
        return objectMapper.readValue(text,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> skipped(String code, String summary) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("skipped", true);
        body.put("code", code);
        body.put("summary", summary);
        return body;
    }

    private static Object limited(Object value, int limit) {
        if (value instanceof List<?> list) {
            return list.subList(0, Math.min(list.size(), limit));
        }
        return List.of();
    }

    private static int rankOf(Map<String, Object> item) {
        var rank = optionalInt(item.get("llm_rerank_rank"));
        return rank == null ? 9999 : rank;
    }

    private static Integer optionalInt(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double numberOf(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).strip());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double boundedDouble(Object value, double fallback, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, numberOf(value, fallback)));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonEmpty(String... values) {
        for (var value : values) {
            if (value != null && !value.isEmpty() && !"null".equals(value)) {
                return value;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }
}

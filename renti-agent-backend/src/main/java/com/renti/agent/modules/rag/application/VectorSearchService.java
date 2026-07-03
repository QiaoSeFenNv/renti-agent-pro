package com.renti.agent.modules.rag.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.infrastructure.client.DeepSeekClient;
import com.renti.agent.infrastructure.client.JinaEmbeddingClient;
import com.renti.agent.infrastructure.client.QdrantClient;
import com.renti.agent.modules.graph.application.GraphQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.boundedInt;
import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.text;

/**
 * 语义召回：Qdrant 向量检索 + MQE/HyDE 查询扩展 + Neo4j 关系上下文。
 * 行为与响应对齐旧 rag/listing_indexer.py search_listing_vectors_payload 及
 * fusion/config 中的本地扩展模板。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private final JinaEmbeddingClient jinaEmbeddingClient;
    private final QdrantClient qdrantClient;
    private final RagConfigService ragConfigService;
    private final GraphQueryService graphQueryService;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    /**
     * 语义召回命中列表（给 search/agent 模块用）。
     *
     * @return 命中列表（含 score 与 payload 摘要字段），失败或未配置时为空列表
     */
    public List<Map<String, Object>> search(String text, String city, int limit) {
        var payload = searchPayload(text, city, limit);
        if (!Boolean.TRUE.equals(payload.get("ok"))) {
            return List.of();
        }
        var matches = payload.get("matches");
        var result = new ArrayList<Map<String, Object>>();
        if (matches instanceof List<?> rows) {
            for (var row : rows) {
                if (row instanceof Map<?, ?>) {
                    result.add(asMap(row));
                }
            }
        }
        return result;
    }

    /** POST /api/admin/rag/qdrant/search {text, city, limit} 完整响应 */
    public Map<String, Object> searchPayload(String text, String city, Integer limit) {
        var cleanText = String.join(" ", (text == null ? "" : text).strip().split("\\s+"));
        if (cleanText.isEmpty()) {
            return error("query_required", "请提供语义检索文本。");
        }
        var rag = ragConfigService.effectiveRag();
        if (!ragConfigService.qdrantConfigured(rag)) {
            return error("not_configured", "Qdrant 或 embedding 配置不完整，无法执行语义检索。");
        }
        int resultLimit = boundedInt(limit, 8, 1, 50);
        int multiplier = boundedInt(rag.get("candidatePoolMultiplier"), 4, 1, 10);
        int candidateLimit = Math.max(resultLimit, Math.min(resultLimit * multiplier, 200));

        try {
            qdrantClient.ensurePayloadIndexes();
        } catch (Exception exception) {
            log.debug("Qdrant payload index ensure skipped: {}", exception.getMessage());
        }

        var queryPlan = expandedSearchQueries(cleanText, rag);
        try {
            var merged = new LinkedHashMap<String, Map<String, Object>>();
            for (var query : queryPlan) {
                var vector = jinaEmbeddingClient.embedQuery(query.get("text"));
                var filters = new LinkedHashMap<String, Object>();
                filters.put("city", city == null ? "" : city);
                filters.put("status", "active");
                var rows = qdrantClient.search(vector, candidateLimit, filters);
                for (var row : rows) {
                    var match = matchFromRow(row);
                    var listingId = String.valueOf(match.get("listingId"));
                    if (listingId.isEmpty()) {
                        continue;
                    }
                    match.put("matchedQuery", query.getOrDefault("text", ""));
                    match.put("expansionStrategy", query.getOrDefault("strategy", "original"));
                    match.put("expansionLabel", query.getOrDefault("label", ""));
                    var current = merged.get(listingId);
                    if (current == null || score(match) > score(current)) {
                        merged.put(listingId, match);
                    }
                }
            }
            var matches = merged.values().stream()
                    .sorted((left, right) -> Double.compare(score(right), score(left)))
                    .limit(resultLimit)
                    .toList();
            var listingIds = matches.stream().map(match -> String.valueOf(match.get("listingId"))).toList();
            var graphContext = graphQueryService.related(listingIds);

            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("collection", qdrantClient.collectionName());
            body.put("query", cleanText);
            body.put("searchQueries", queryPlan);
            body.put("candidatePoolMultiplier", multiplier);
            body.put("matches", matches);
            body.put("graphContext", graphContext);
            body.put("total", matches.size());
            body.put("summary", searchSummary(matches.size(), graphContext));
            return body;
        } catch (Exception exception) {
            log.warn("Vector search failed: {}", exception.getMessage());
            return error("search_failed", "Qdrant 语义检索失败：" + exception.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ 查询扩展

    /** 对齐旧 _expanded_search_queries：LLM 优先（可用时），否则本地模板 */
    List<Map<String, String>> expandedSearchQueries(String text, Map<String, Object> rag) {
        var mqeEnabled = Boolean.TRUE.equals(rag.get("mqeEnabled"));
        var hydeEnabled = Boolean.TRUE.equals(rag.get("hydeEnabled"));
        var mqeQueryCount = boundedInt(rag.get("mqeQueryCount"), 3, 1, 8);
        if ("llm".equals(text(rag.get("queryExpansionProvider"), "local")) && (mqeEnabled || hydeEnabled)) {
            var llmQueries = llmExpandedSearchQueries(text, mqeEnabled, hydeEnabled, mqeQueryCount);
            if (!llmQueries.isEmpty()) {
                return llmQueries;
            }
        }
        var queries = new ArrayList<Map<String, String>>();
        queries.add(query("0", "original", "原始查询", text));
        var seen = new LinkedHashSet<String>();
        seen.add(text);
        if (mqeEnabled) {
            for (var variant : mqeQueryVariants(text, mqeQueryCount)) {
                if (seen.add(variant)) {
                    queries.add(query(String.valueOf(queries.size()), "mqe",
                            "MQE-" + queries.size(), variant));
                }
            }
        }
        if (hydeEnabled) {
            var hyde = hydeDocument(text);
            if (!seen.contains(hyde)) {
                queries.add(query(String.valueOf(queries.size()), "hyde", "HyDE 假设文档", hyde));
            }
        }
        return queries;
    }

    /** 对齐旧 _llm_expanded_search_queries：DeepSeek 生成 MQE/HyDE，失败返回空走本地 */
    private List<Map<String, String>> llmExpandedSearchQueries(String text, boolean mqeEnabled,
                                                               boolean hydeEnabled, int mqeQueryCount) {
        if (!deepSeekClient.isConfigured()) {
            return List.of();
        }
        Map<String, Object> parsed;
        try {
            var request = new LinkedHashMap<String, Object>();
            request.put("query", text);
            request.put("mqeEnabled", mqeEnabled);
            request.put("mqeQueryCount", mqeQueryCount);
            request.put("hydeEnabled", hydeEnabled);
            var content = deepSeekClient.chat(List.of(
                    DeepSeekClient.ChatMessage.system("""
                            你是租房检索 query expansion 生成器。只输出 JSON。\
                            根据用户查询生成语义等价但表达多样的 MQE 查询，以及可选 HyDE 假设房源描述。\
                            不要编造具体不存在的小区名；保留城市、预算、户型、地铁、通勤等硬条件。\
                            格式：{"mqe":["..."],"hyde":"..."}。"""),
                    DeepSeekClient.ChatMessage.user(objectMapper.writeValueAsString(request))), 0.2);
            parsed = extractJsonObject(content);
        } catch (Exception exception) {
            log.debug("LLM query expansion failed, fallback to local templates: {}", exception.getMessage());
            return List.of();
        }
        var queries = new ArrayList<Map<String, String>>();
        queries.add(query("0", "original", "原始查询", text));
        var seen = new LinkedHashSet<String>();
        seen.add(text);
        if (mqeEnabled && parsed.get("mqe") instanceof List<?> rawMqe) {
            for (var variant : rawMqe) {
                var clean = String.join(" ", String.valueOf(variant == null ? "" : variant).split("\\s+"));
                clean = clean.length() > 1000 ? clean.substring(0, 1000) : clean;
                if (!clean.isEmpty() && seen.add(clean)) {
                    queries.add(query(String.valueOf(queries.size()), "mqe_llm",
                            "LLM-MQE-" + queries.size(), clean));
                }
                if (queries.size() >= mqeQueryCount + 1) {
                    break;
                }
            }
        }
        if (hydeEnabled) {
            var hyde = String.join(" ", String.valueOf(parsed.get("hyde") == null ? "" : parsed.get("hyde")).split("\\s+"));
            hyde = hyde.length() > 1600 ? hyde.substring(0, 1600) : hyde;
            if (!hyde.isEmpty() && !seen.contains(hyde)) {
                queries.add(query(String.valueOf(queries.size()), "hyde_llm", "LLM-HyDE 假设文档", hyde));
            }
        }
        return queries.size() > 1 ? queries : List.of();
    }

    /** 本地 MQE 变体模板：照抄旧 _mqe_query_variants */
    static List<String> mqeQueryVariants(String text, int count) {
        var clean = String.join(" ", (text == null ? "" : text).strip().split("\\s+"));
        var variants = List.of(
                clean.replace("一室一厅", "1室1厅").replace("一居室", "1室1厅"),
                clean.replace("近地铁", "地铁站步行距离短 通勤方便"),
                clean.replace("便宜", "租金低 预算友好").replace("最低价", "价格从低到高"),
                clean + " 房源 小区 租金 户型 地铁 通勤 配套",
                "适合租住的房源：" + clean + "，关注价格、位置、地铁距离和生活便利度");
        return variants.stream()
                .filter(variant -> !variant.isEmpty() && !variant.equals(clean))
                .limit(Math.max(0, count))
                .toList();
    }

    /** 本地 HyDE 假设文档模板：照抄旧 _hyde_document */
    static String hydeDocument(String text) {
        var clean = String.join(" ", (text == null ? "" : text).strip().split("\\s+"));
        return """
                这是一套符合用户租房需求的理想房源描述。
                用户需求：%s
                房源应位于目标城市或目标地点附近，户型、预算、面积、楼层、地铁距离、通勤时间和生活配套与需求高度匹配。\
                房源标题、社区、小区标签、交通信息和风险提示中应能体现这些条件。""".formatted(clean);
    }

    // ------------------------------------------------------------------ helpers

    /** 对齐旧 _match_from_qdrant_row */
    private Map<String, Object> matchFromRow(Map<String, Object> row) {
        var payload = asMap(row.get("payload"));
        var match = new LinkedHashMap<String, Object>();
        match.put("listingId", firstNonEmpty(text(payload.get("listing_id"), ""),
                text(payload.get("id"), ""), row.get("id") == null ? "" : String.valueOf(row.get("id"))));
        match.put("score", row.get("score") instanceof Number number ? number.doubleValue() : 0.0);
        match.put("title", text(payload.get("title"), ""));
        match.put("community", text(payload.get("community"), ""));
        match.put("city", text(payload.get("city"), ""));
        match.put("district", text(payload.get("district"), ""));
        match.put("businessArea", text(payload.get("business_area"), ""));
        match.put("rentPrice", payload.get("rent_price"));
        match.put("layout", text(payload.get("layout"), ""));
        match.put("tags", payload.get("tags") instanceof List<?> tags ? tags : List.of());
        match.put("source", text(payload.get("source"), ""));
        match.put("sourceUrl", text(payload.get("source_url"), ""));
        return match;
    }

    private String searchSummary(int matchCount, Map<String, Object> graphContext) {
        if (Boolean.TRUE.equals(graphContext.get("ok"))) {
            int itemCount = graphContext.get("items") instanceof List<?> items ? items.size() : 0;
            return "Qdrant 语义检索命中 %d 套房源，Neo4j 关系增强返回 %d 条上下文。".formatted(matchCount, itemCount);
        }
        var summary = text(graphContext.get("summary"), "Neo4j 关系增强未启用。");
        return "Qdrant 语义检索命中 %d 套房源；%s".formatted(matchCount, summary);
    }

    /** 对齐旧 _extract_json_object：兼容 ```json 代码块与嵌入文本 */
    Map<String, Object> extractJsonObject(String content) throws Exception {
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
                throw new IllegalStateException("missing json object");
            }
            text = text.substring(start, end + 1);
        }
        return objectMapper.readValue(text,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    private static Map<String, String> query(String index, String strategy, String label, String text) {
        var value = new LinkedHashMap<String, String>();
        value.put("index", index);
        value.put("strategy", strategy);
        value.put("label", label);
        value.put("text", text);
        return value;
    }

    private static double score(Map<String, Object> match) {
        return match.get("score") instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static String firstNonEmpty(String... values) {
        for (var value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private static Map<String, Object> error(String code, String summary) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("code", code);
        body.put("summary", summary);
        return body;
    }
}

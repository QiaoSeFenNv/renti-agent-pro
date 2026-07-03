package com.renti.agent.modules.agent.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.modules.listing.application.ListingQueryService;
import com.renti.agent.modules.rag.application.VectorSearchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * rental-search 降级链路（Python Agent 服务失败/ok:false 时）。
 *
 * <p>优先反射调用 search 模块的 MapIntentService.handleMapIntent(userId, payload)
 * （B4 并行开发，编译期尚不存在）；未接入时用 VectorSearchService + ListingQueryService
 * 在本模块直接拼装推荐结果。结果统一标记 agent.mode = "rules_fallback"。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RentalSearchFallbackService {

    private static final String MAP_INTENT_SERVICE_CLASS = "com.renti.agent.modules.search.application.MapIntentService";
    private static final String FALLBACK_WARNING = "AI Agent 服务不可用，已降级为规则检索链路。";

    private final ApplicationContext applicationContext;
    private final VectorSearchService vectorSearchService;
    private final ListingQueryService listingQueryService;

    public Map<String, Object> fallback(Long userId, Map<String, Object> request, String reason) {
        var viaMapIntent = tryMapIntentService(userId, request);
        var result = viaMapIntent != null ? viaMapIntent : localFallback(userId, request);
        return decorate(result, userId, reason);
    }

    /** B4 的 MapIntentService 就绪后自动切换到规则链路正主；当前通过反射探测。 */
    private Map<String, Object> tryMapIntentService(Long userId, Map<String, Object> request) {
        try {
            var clazz = Class.forName(MAP_INTENT_SERVICE_CLASS);
            var bean = applicationContext.getBean(clazz);
            var method = clazz.getMethod("handleMapIntent", Long.class, Map.class);
            if (method.invoke(bean, userId, request) instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                var result = (Map<String, Object>) map;
                return new LinkedHashMap<>(result);
            }
        } catch (ClassNotFoundException e) {
            log.debug("MapIntentService 尚未接入（B4 并行开发中），使用本模块降级组装。");
        } catch (Exception e) {
            log.warn("MapIntentService 调用失败，使用本模块降级组装：{}", e.getMessage());
        }
        return null;
    }

    private Map<String, Object> localFallback(Long userId, Map<String, Object> request) {
        var query = AgentPayloads.text(request.get("query"));
        var city = AgentPayloads.text(request.get("city"), "上海");
        var settings = AgentPayloads.mapValue(request.get("settings"));
        var limit = Math.max(1, Math.min(50,
                settings.get("listingPageSize") == null ? 10 : AgentPayloads.optionalInt(settings.get("listingPageSize"))));

        var byId = listingQueryService.findActiveByCity(city).stream()
                .collect(Collectors.toMap(ListingEntity::getListingId, Function.identity(), (a, b) -> a));

        List<Map<String, Object>> recommendations = new ArrayList<>();
        if (!query.isEmpty()) {
            for (var hit : vectorSearchService.search(query, city, limit * 2)) {
                var listingId = AgentPayloads.text(hit.get("listingId"));
                var entity = byId.get(listingId);
                if (entity == null) {
                    continue;
                }
                var score = hit.get("score") instanceof Number number ? number.doubleValue() : 0.0;
                recommendations.add(recommendation(entity, 55 + Math.min(35, score * 40),
                        List.of("语义检索匹配（降级链路）")));
                if (recommendations.size() >= limit) {
                    break;
                }
            }
        }
        if (recommendations.isEmpty()) {
            recommendations = byId.values().stream()
                    .sorted((a, b) -> Integer.compare(a.getRentPrice(), b.getRentPrice()))
                    .limit(limit)
                    .map(entity -> recommendation(entity, 55, List.of("按城市在租房源推荐（降级链路）")))
                    .toList();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("intent", "rent_search_nearby");
        result.put("queryText", query);
        result.put("parsed", Map.of(
                "city", city,
                "locationText", "",
                "radiusMeters", request.getOrDefault("radiusMeters", 2000),
                "sort", AgentPayloads.text(settings.get("defaultSort"), "score_desc"),
                "constraints", Map.of()));
        result.put("center", request.get("center"));
        result.put("radiusMeters", request.getOrDefault("radiusMeters", 2000));
        result.put("recommendations", recommendations);
        result.put("markers", recommendations.stream()
                .map(row -> Map.of(
                        "id", AgentPayloads.text(row.get("listingId")),
                        "title", AgentPayloads.text(row.get("title")),
                        "longitude", row.get("longitude"),
                        "latitude", row.get("latitude"),
                        "rentPrice", row.get("rentPrice")))
                .toList());
        result.put("toolTrace", List.of(Map.of(
                "tool", "rules_fallback",
                "status", "ok",
                "summary", "已用本地规则链路（向量召回 + 城市房源）拼装推荐 " + recommendations.size() + " 条。")));
        result.put("summary", recommendations.isEmpty()
                ? "AI 服务暂不可用，且未找到匹配房源，请稍后重试或调整条件。"
                : "AI 服务暂不可用，已按规则链路推荐 " + city + " 的 " + recommendations.size() + " 套房源。");
        result.put("warnings", new ArrayList<String>());
        result.put("empty", recommendations.isEmpty());
        return result;
    }

    private Map<String, Object> recommendation(ListingEntity entity, double score, List<String> reasons) {
        Map<String, Object> row = new LinkedHashMap<>(AgentPayloads.listingMap(entity));
        row.put("score", Math.round(score * 10) / 10.0);
        row.put("reasons", reasons);
        row.put("riskNotes", entity.getRiskTags() == null ? List.of() : entity.getRiskTags());
        row.put("distanceM", null);
        row.put("withinRadius", false);
        row.put("match", reasons.isEmpty() ? "综合匹配" : reasons.get(0));
        return row;
    }

    private Map<String, Object> decorate(Map<String, Object> result, Long userId, String reason) {
        var agent = new LinkedHashMap<>(AgentPayloads.mapValue(result.get("agent")));
        agent.putIfAbsent("name", "rental-search-fallback");
        agent.putIfAbsent("version", "2.0");
        agent.put("mode", "rules_fallback");
        agent.put("userId", userId);
        result.put("agent", agent);

        var warnings = new ArrayList<>(result.get("warnings") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.<String>of());
        warnings.add(FALLBACK_WARNING + (reason == null || reason.isBlank() ? "" : "原因：" + reason));
        result.put("warnings", warnings);
        return result;
    }
}

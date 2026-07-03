package com.renti.agent.modules.agent.application;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.renti.agent.infrastructure.client.AgentServiceClient;
import com.renti.agent.modules.admin.application.AgentTraceService;
import com.renti.agent.modules.admin.application.RetrievalAuditService;
import com.renti.agent.modules.admin.application.UserInteractionService;
import com.renti.agent.modules.user.application.SearchHistoryService;
import com.renti.agent.modules.user.application.UserSettingsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * rental-search 桥接：组装契约 §A 请求 → 调 Python LangGraph 服务 → 失败降级规则链路。
 * 横切：AgentTrace + 搜索历史 + 检索审计 + 用户交互（对齐 API-CONTRACT 横切行为表）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSearchService {

    private static final String ENDPOINT = "/api/agent/rental-search";

    private final AgentServiceClient agentServiceClient;
    private final RentalSearchFallbackService fallbackService;
    private final UserSettingsService userSettingsService;
    private final AgentTraceService agentTraceService;
    private final SearchHistoryService searchHistoryService;
    private final RetrievalAuditService retrievalAuditService;
    private final UserInteractionService userInteractionService;

    public Map<String, Object> rentalSearch(Long userId, Map<String, Object> payload) {
        long started = System.currentTimeMillis();
        var request = buildRequest(userId, payload);

        Map<String, Object> result;
        String errorMessage = null;
        try {
            result = agentServiceClient.rentalSearch(request);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                var reason = AgentPayloads.text(result.get("summary"), AgentPayloads.text(result.get("code"), "ok:false"));
                log.info("Agent 服务返回失败，走降级链路 userId={} reason={}", userId, reason);
                result = fallbackService.fallback(userId, request, reason);
            }
        } catch (Exception e) {
            errorMessage = e.getMessage();
            log.warn("Agent 服务调用异常，走降级链路 userId={} error={}", userId, errorMessage);
            result = fallbackService.fallback(userId, request, errorMessage);
        }

        long durationMs = System.currentTimeMillis() - started;
        recordCrossCutting(userId, request, result, durationMs, errorMessage);
        return result;
    }

    private Map<String, Object> buildRequest(Long userId, Map<String, Object> payload) {
        var settings = userSettingsService.getSettings(userId);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", userId);
        request.put("query", AgentPayloads.text(payload.get("query"), AgentPayloads.text(payload.get("text"))));
        request.put("city", AgentPayloads.text(payload.get("city"), "上海"));
        request.put("source", AgentPayloads.text(payload.get("source"), "text"));
        var center = AgentPayloads.mapValue(payload.get("center"));
        if (!center.isEmpty()) {
            request.put("center", center);
        }
        var radius = AgentPayloads.optionalInt(payload.get("radiusMeters"));
        if (radius == null) {
            radius = AgentPayloads.optionalInt(settings.get("defaultRadiusMeters"));
        }
        request.put("radiusMeters", radius == null ? 2000 : radius);
        request.put("settings", Map.of(
                "modelProfile", AgentPayloads.text(settings.get("modelProfile"), "balanced"),
                "defaultSort", AgentPayloads.text(settings.get("defaultSort"), "score_desc"),
                "listingPageSize", settings.get("listingPageSize") == null ? 10 : settings.get("listingPageSize")));
        return request;
    }

    private void recordCrossCutting(
            Long userId,
            Map<String, Object> request,
            Map<String, Object> result,
            long durationMs,
            String errorMessage) {
        var query = AgentPayloads.text(request.get("query"));
        var city = AgentPayloads.text(request.get("city"), "上海");
        agentTraceService.record(userId, query, city, "system_search", request, result, durationMs, errorMessage);
        searchHistoryService.record(userId, request, result);
        retrievalAuditService.record(userId, ENDPOINT, request, result, durationMs);
        userInteractionService.record(userId, ENDPOINT, request, result, durationMs, errorMessage);
    }
}

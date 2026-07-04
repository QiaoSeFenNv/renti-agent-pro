package com.renti.agent.modules.search.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentUser;
import com.renti.agent.modules.admin.application.RetrievalAuditService;
import com.renti.agent.modules.admin.application.UserInteractionService;
import com.renti.agent.modules.auth.application.UserPrincipal;
import com.renti.agent.modules.search.application.MapIntentService;
import com.renti.agent.modules.search.application.PlaceService;
import com.renti.agent.modules.search.application.RecommendationService;
import com.renti.agent.modules.search.application.RequirementParseService;
import com.renti.agent.modules.user.application.SearchHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * search 模块用户端点（契约 §search，需登录）。
 * 横切记录按 API-CONTRACT 横切行为表执行：
 * map-intent 记 搜索历史+检索审计+用户交互；parse/recommendations/map-target 记 用户交互。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final PlaceService placeService;
    private final RequirementParseService requirementParseService;
    private final RecommendationService recommendationService;
    private final MapIntentService mapIntentService;
    private final SearchHistoryService searchHistoryService;
    private final RetrievalAuditService retrievalAuditService;
    private final UserInteractionService userInteractionService;

    @PostMapping("/api/places/resolve")
    public Map<String, Object> resolvePlace(
            @CurrentUser UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> payload) {
        return placeService.resolvePlacePayload(orEmpty(payload));
    }

    @PostMapping("/api/locations/geocode")
    public Map<String, Object> geocode(
            @CurrentUser UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> payload) {
        return placeService.resolveLocationGeocodePayload(orEmpty(payload));
    }

    @PostMapping("/api/requirements/parse")
    public Map<String, Object> parseRequirements(
            @CurrentUser UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> payload) {
        long started = System.currentTimeMillis();
        var result = requirementParseService.parsePayload(orEmpty(payload));
        userInteractionService.record(user.id(), "/api/requirements/parse",
                orEmpty(payload), result, System.currentTimeMillis() - started);
        return result;
    }

    @PostMapping("/api/recommendations/search")
    public Map<String, Object> searchRecommendations(
            @CurrentUser UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> payload) {
        long started = System.currentTimeMillis();
        var result = recommendationService.searchPayload(orEmpty(payload));
        userInteractionService.record(user.id(), "/api/recommendations/search",
                orEmpty(payload), result, System.currentTimeMillis() - started);
        return result;
    }

    @PostMapping("/api/search/map-intent")
    public Map<String, Object> mapIntent(
            @CurrentUser UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> payload) {
        long started = System.currentTimeMillis();
        log.info("map-intent userId={}", user.id());
        var request = orEmpty(payload);
        var result = mapIntentService.handleMapIntent(user.id(), request);
        long durationMs = System.currentTimeMillis() - started;
        searchHistoryService.record(user.id(), request, result);
        retrievalAuditService.record(user.id(), "/api/search/map-intent", request, result, durationMs);
        userInteractionService.record(user.id(), "/api/search/map-intent", request, result, durationMs);
        return result;
    }

    @PostMapping("/api/search/map-target")
    public Map<String, Object> mapTarget(
            @CurrentUser UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> payload) {
        long started = System.currentTimeMillis();
        var request = orEmpty(payload);
        var result = mapIntentService.handleMapTarget(user.id(), request);
        userInteractionService.record(user.id(), "/api/search/map-target",
                request, result, System.currentTimeMillis() - started);
        return result;
    }

    private static Map<String, Object> orEmpty(Map<String, Object> payload) {
        return payload == null ? Map.of() : payload;
    }
}

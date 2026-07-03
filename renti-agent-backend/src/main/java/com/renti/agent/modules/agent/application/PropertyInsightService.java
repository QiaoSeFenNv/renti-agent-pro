package com.renti.agent.modules.agent.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.renti.agent.infrastructure.client.AgentServiceClient;
import com.renti.agent.infrastructure.client.DeepSeekClient;
import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.modules.admin.application.AgentTraceService;
import com.renti.agent.modules.admin.application.UserInteractionService;
import com.renti.agent.modules.listing.application.ListingQueryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * property-insight 桥接：调 Python 服务；失败降级 DeepSeekClient 直连生成简化 insight；
 * 再失败返回规则文案（任何情况下不抛 500）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyInsightService {

    private static final String ENDPOINT = "/api/agent/property-insight";

    private final AgentServiceClient agentServiceClient;
    private final DeepSeekClient deepSeekClient;
    private final ListingQueryService listingQueryService;
    private final AgentTraceService agentTraceService;
    private final UserInteractionService userInteractionService;

    public Map<String, Object> insight(Long userId, Map<String, Object> payload) {
        long started = System.currentTimeMillis();
        var listingId = AgentPayloads.text(payload.get("listingId"));
        if (listingId.isEmpty()) {
            return Map.of("ok", false, "code", "listing_id_required", "summary", "请提供 listingId。");
        }
        var entity = listingQueryService.findActive(listingId).orElse(null);
        if (entity == null) {
            return Map.of("ok", false, "code", "not_found", "summary", "房源不存在或已下架。");
        }

        var listing = AgentPayloads.listingMap(entity);
        var focus = AgentPayloads.text(payload.get("focus"), "balanced");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", userId);
        request.put("listingId", listingId);
        request.put("listing", listing);
        request.put("focus", focus);

        Map<String, Object> result;
        String errorMessage = null;
        try {
            result = agentServiceClient.propertyInsight(request);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                errorMessage = AgentPayloads.text(result.get("summary"), "agent_error");
                result = deepSeekFallback(userId, entity, listing, errorMessage);
            }
        } catch (Exception e) {
            errorMessage = e.getMessage();
            result = deepSeekFallback(userId, entity, listing, errorMessage);
        }

        long durationMs = System.currentTimeMillis() - started;
        agentTraceService.record(userId, "房源详情分析：" + AgentPayloads.text(entity.getTitle(), listingId),
                AgentPayloads.text(entity.getCity(), "上海"), "property_insight", request, result, durationMs, errorMessage);
        userInteractionService.record(userId, ENDPOINT, request, result, durationMs, errorMessage);
        return result;
    }

    private Map<String, Object> deepSeekFallback(
            Long userId, ListingEntity entity, Map<String, Object> listing, String reason) {
        String insightText = null;
        var mode = "rules_fallback";
        if (deepSeekClient.isConfigured()) {
            try {
                insightText = deepSeekClient.chat(List.of(
                        DeepSeekClient.ChatMessage.system("""
                                你是房源分析助手。基于给定房源 JSON 数据，用不超过 200 字的中文输出一段客观分析：
                                覆盖租金性价比、通勤/地铁可达性、居住环境与需核验的风险。不得编造数据，字段缺失要说明待核验。
                                只输出分析文本。"""),
                        DeepSeekClient.ChatMessage.user(String.valueOf(listing))), 0.3);
                mode = "llm_direct";
            } catch (Exception e) {
                log.warn("DeepSeek 直连生成 insight 失败，使用规则文案：{}", e.getMessage());
            }
        }

        int environmentScore = environmentScore(entity);
        int commuteScore = commuteScore(entity);
        int valueScore = valueScore(entity, environmentScore, commuteScore);
        var title = AgentPayloads.text(entity.getTitle(), "当前房源");
        if (insightText == null || insightText.isBlank()) {
            insightText = title + " 的分析已按规则生成：综合评分 " + valueScore + " 分（环境 " + environmentScore
                    + " 分、通勤 " + commuteScore + " 分）。建议重点核验来源链接、可租状态与合同细节。";
        }

        var computedAt = OffsetDateTime.now().toString();
        var riskTags = entity.getRiskTags() == null ? List.<String>of() : entity.getRiskTags();
        Map<String, Object> detailPatch = new LinkedHashMap<>();
        detailPatch.put("insight", insightText);
        detailPatch.put("pros", entity.getTags() == null ? List.of() : entity.getTags());
        detailPatch.put("cons", riskTags.isEmpty() ? List.of("价格、图片、可租状态仍需以来源平台为准。") : riskTags);
        detailPatch.put("score", valueScore);
        detailPatch.put("valueIndex", Map.of(
                "score", valueScore,
                "summary", "综合评分 " + valueScore + " 分：居住环境 " + environmentScore + " 分，通勤效率 " + commuteScore + " 分。",
                "factors", List.of(
                        Map.of("label", "居住环境", "value", environmentScore + " 分"),
                        Map.of("label", "通勤效率", "value", commuteScore + " 分"),
                        Map.of("label", "租金面积", "value", entity.getRentPrice() + " 元/月 / "
                                + (entity.getAreaSqm() == null ? "面积待补" : entity.getAreaSqm() + "㎡"))),
                "evidence", List.of()));
        detailPatch.put("environmentEvaluation", Map.of(
                "score", environmentScore,
                "summary", "环境评分 " + environmentScore + " 分，按房源标签与地铁距离折算。",
                "factors", List.of()));
        detailPatch.put("commuteEvaluation", Map.of(
                "score", commuteScore,
                "summary", "通勤评分 " + commuteScore + " 分，按地铁距离与通勤分钟数折算。",
                "factors", List.of(),
                "routeNote", "地图展示为直线距离；路线距离和时长以后端高德距离服务为准。"));
        detailPatch.put("analysisMeta", Map.of(
                "status", "partial",
                "source", mode.equals("llm_direct") ? "deepseek_direct" : "local_fallback",
                "computedAt", computedAt,
                "warnings", List.of("AI Agent 服务不可用，已降级生成。" + (reason == null ? "" : "原因：" + reason)),
                "model", "",
                "agentMode", mode));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("listingId", entity.getListingId());
        result.put("cacheHit", false);
        result.put("summary", insightText);
        result.put("analysis", Map.of(
                "status", "partial",
                "source", mode.equals("llm_direct") ? "deepseek_direct" : "local_fallback",
                "computedAt", computedAt,
                "warnings", List.of(),
                "insight", Map.of("summary", insightText, "score", valueScore)));
        result.put("detailPatch", detailPatch);
        result.put("toolTrace", List.of(Map.of(
                "tool", "property_insight_fallback",
                "status", mode,
                "summary", "Agent 服务不可用，已用" + (mode.equals("llm_direct") ? " DeepSeek 直连" : "本地规则") + "生成简化分析。")));
        result.put("agent", Map.of(
                "name", "PropertyInsightAgent",
                "version", "property-insight-v2",
                "mode", mode,
                "status", "fallback",
                "provider", mode.equals("llm_direct") ? "deepseek" : "local",
                "model", "",
                "usage", Map.of(),
                "userId", userId));
        return result;
    }

    private int environmentScore(ListingEntity entity) {
        int score = 54;
        var tags = entity.getTags() == null ? List.<String>of() : entity.getTags();
        score += Math.min(18, tags.size() * 3);
        if (entity.getMetroDistanceM() != null && entity.getMetroDistanceM() > 0 && entity.getMetroDistanceM() <= 1000) {
            score += 10;
        }
        if (entity.getAreaSqm() != null && entity.getAreaSqm() >= 35) {
            score += 4;
        }
        if (entity.getLayout() != null && !entity.getLayout().isBlank()) {
            score += 4;
        }
        var risks = entity.getRiskTags() == null ? List.<String>of() : entity.getRiskTags();
        score -= Math.min(12, risks.size() * 4);
        return Math.max(20, Math.min(95, score));
    }

    private int commuteScore(ListingEntity entity) {
        int score = 62;
        var duration = entity.getCommuteMinutes();
        if (duration != null && duration > 0) {
            score += duration <= 25 ? 20 : duration <= 45 ? 8 : duration >= 75 ? -18 : -6;
        }
        var metroDistance = entity.getMetroDistanceM();
        if (metroDistance != null && metroDistance > 0) {
            score += metroDistance <= 800 ? 10 : metroDistance >= 1800 ? -8 : 0;
        }
        return Math.max(20, Math.min(96, score));
    }

    private int valueScore(ListingEntity entity, int environmentScore, int commuteScore) {
        int base = (int) Math.round(environmentScore * 0.42 + commuteScore * 0.34 + 55 * 0.24);
        int price = entity.getRentPrice();
        var area = entity.getAreaSqm();
        if (price > 0 && area != null && area > 0) {
            double unitPrice = (double) price / area;
            base += unitPrice <= 120 ? 10 : unitPrice <= 180 ? 4 : unitPrice >= 260 ? -12 : 0;
        } else if (price > 0) {
            base += price <= 4500 ? 5 : price >= 9000 ? -8 : 0;
        }
        if (entity.getLayout() != null && !entity.getLayout().isBlank()) {
            base += 3;
        }
        return Math.max(20, Math.min(96, base));
    }
}

package com.renti.agent.modules.search.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.renti.agent.infrastructure.client.AmapClient;
import com.renti.agent.modules.user.application.UserSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.renti.agent.modules.search.application.SearchSupport.DEFAULT_CITY;
import static com.renti.agent.modules.search.application.SearchSupport.asMap;
import static com.renti.agent.modules.search.application.SearchSupport.cleanText;
import static com.renti.agent.modules.search.application.SearchSupport.distanceMeters;
import static com.renti.agent.modules.search.application.SearchSupport.doubleOr;
import static com.renti.agent.modules.search.application.SearchSupport.firstNonEmpty;
import static com.renti.agent.modules.search.application.SearchSupport.intOr;
import static com.renti.agent.modules.search.application.SearchSupport.optionalDouble;
import static com.renti.agent.modules.search.application.SearchSupport.optionalInt;
import static com.renti.agent.modules.search.application.SearchSupport.queryTokens;
import static com.renti.agent.modules.search.application.SearchSupport.str;

/**
 * 地图工作台核心链路：自然语言/地图点击 → 需求解析 + 中心点定位 + 半径召回 + 启发式评分。
 * 行为对齐旧 map_intent.py（intent: rent_search_nearby | needs_clarification；
 * 响应结构与 agent rental-search 同构，见 AGENT-SERVICE-CONTRACT §A）。
 *
 * <p>横切记录（搜索历史/检索审计/用户交互）由 SearchController 按端点执行；
 * 本服务保持纯计算，handleMapIntent(Long, Map) 签名同时供
 * RentalSearchFallbackService 反射调用（agent 降级链路，避免二次记录）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapIntentService {

    /** 含数字或明显约束词的 token 不作为定位关键词 */
    private static final Pattern CONSTRAINT_TOKEN = Pattern.compile(
            ".*([0-9０-９]|预算|万元|居室|一居|两居|三居|室|厅|整租|合租|通勤|分钟|安静|太吵|电梯|朝南|精装|老小区|中介).*");

    private static final int MAX_RECOMMENDATIONS = 24;

    private final RequirementParseService requirementParseService;
    private final RecommendationService recommendationService;
    private final ListingSearchService listingSearchService;
    private final PlaceService placeService;
    private final AmapClient amapClient;
    private final UserSettingsService userSettingsService;

    /** POST /api/search/map-intent：意图解析 + 周边推荐 + markers + toolTrace */
    public Map<String, Object> handleMapIntent(Long userId, Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var queryText = cleanText(firstNonEmpty(
                str(source.get("queryText")), str(source.get("query")), str(source.get("text"))), 200);
        var city = firstNonEmpty(cleanText(source.get("city"), 40), DEFAULT_CITY);
        var searchSource = firstNonEmpty(str(source.get("source")), "text");
        var settings = userSettingsService.getSettings(userId);
        var radius = resolveRadius(source, settings);
        var sort = firstNonEmpty(str(source.get("sort")), str(settings.get("defaultSort")), "score_desc");

        var toolTrace = new ArrayList<Map<String, Object>>();
        var warnings = new ArrayList<String>();

        var requirement = requirementParseService.parseRequirementText(queryText, null);
        requirement.put("city", city);
        requirement.put("sort", sort);
        toolTrace.add(trace("parse_intent", "ok", parseSummary(requirement)));

        var center = resolveCenter(source, queryText, city, toolTrace, warnings);
        var locationText = center == null ? extractLocationKeyword(queryText) : str(center.remove("locationText"));
        if (center != null) {
            requirement.put("targetPlace", requirementParseService.selectedPlaceFrom(Map.of(
                    "name", str(center.get("label")),
                    "type", "custom_point",
                    "longitude", center.get("longitude"),
                    "latitude", center.get("latitude"),
                    "city", city,
                    "suggestedRadiusM", radius)));
        }

        var parsed = parsedPayload(city, locationText, radius, sort, requirement);

        if (needsClarification(queryText, center, requirement)) {
            var result = baseResult(queryText, searchSource, parsed, null, radius, toolTrace, warnings);
            result.put("intent", "needs_clarification");
            result.put("recommendations", List.of());
            result.put("markers", List.of());
            result.put("summary", "还不确定要找哪里的房子：可以描述目标位置和预算（例如“静安寺附近 6000 以内一居室”），或直接在地图上点选一个位置。");
            result.put("empty", true);
            return result;
        }

        var cityListings = listingSearchService.loadByCity(city);
        var pool = new ArrayList<Map<String, Object>>();
        if (center != null) {
            double centerLng = doubleOr(center.get("longitude"), 0);
            double centerLat = doubleOr(center.get("latitude"), 0);
            var withDistance = new ArrayList<Map<String, Object>>();
            for (var listing : cityListings) {
                var row = new LinkedHashMap<>(listing);
                int distance = (int) Math.round(distanceMeters(centerLng, centerLat,
                        doubleOr(listing.get("longitude"), 0), doubleOr(listing.get("latitude"), 0)));
                row.put("distanceM", distance);
                row.put("withinRadius", distance <= radius);
                withDistance.add(row);
            }
            pool.addAll(withDistance.stream().filter(row -> Boolean.TRUE.equals(row.get("withinRadius"))).toList());
            if (pool.isEmpty()) {
                warnings.add("半径 " + radius + " 米内暂无在架房源，已为你展示全城匹配结果。");
                pool.addAll(withDistance);
            }
            toolTrace.add(trace("search_listings", "ok",
                    "以「" + str(center.get("label")) + "」为中心 " + radius + " 米召回 " + pool.size()
                            + " 套（全城在架 " + cityListings.size() + " 套）。"));
        } else {
            for (var listing : cityListings) {
                pool.add(new LinkedHashMap<>(listing));
            }
            toolTrace.add(trace("search_listings", "ok", city + "全城在架房源 " + pool.size() + " 套参与匹配。"));
        }

        var ranked = recommendationService.rankListings(requirement, pool, MAX_RECOMMENDATIONS);
        var recommendations = new ArrayList<Map<String, Object>>();
        for (var item : ranked) {
            var card = new LinkedHashMap<>(item.listing());
            card.put("score", item.score());
            card.put("reasons", item.reasons());
            card.put("riskNotes", item.riskNotes());
            card.put("match", item.reasons().isEmpty() ? "综合匹配" : item.reasons().get(0));
            recommendations.add(card);
        }
        if ("price_asc".equals(sort)) {
            recommendations.sort((left, right) -> Integer.compare(
                    intOr(left.get("rentPrice"), Integer.MAX_VALUE), intOr(right.get("rentPrice"), Integer.MAX_VALUE)));
        }
        toolTrace.add(trace("rank_listings", "ok",
                "启发式评分完成（预算/通勤/户型/地铁/偏好），返回前 " + recommendations.size() + " 套。"));

        var result = baseResult(queryText, searchSource, parsed, center, radius, toolTrace, warnings);
        result.put("intent", "rent_search_nearby");
        result.put("recommendations", recommendations);
        result.put("markers", markers(recommendations));
        result.put("summary", intentSummary(city, center, radius, recommendations.size()));
        result.put("empty", recommendations.isEmpty());
        return result;
    }

    /** POST /api/search/map-target：仅定位不搜房（center + 周边 POI） */
    public Map<String, Object> handleMapTarget(Long userId, Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var queryText = cleanText(firstNonEmpty(
                str(source.get("queryText")), str(source.get("query")),
                str(source.get("locationText")), str(source.get("text"))), 200);
        var city = firstNonEmpty(cleanText(source.get("city"), 40), DEFAULT_CITY);
        var settings = userSettingsService.getSettings(userId);
        var radius = resolveRadius(source, settings);

        var toolTrace = new ArrayList<Map<String, Object>>();
        var warnings = new ArrayList<String>();
        var center = resolveCenter(source, queryText, city, toolTrace, warnings);
        if (center != null) {
            center.remove("locationText");
        }
        if (center == null) {
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", false);
            body.put("code", "location_required");
            body.put("intent", "map_target");
            body.put("queryText", queryText);
            body.put("toolTrace", toolTrace);
            body.put("summary", "未能定位目标位置，请补充更具体的地点关键词或直接在地图上点选。");
            body.put("warnings", warnings);
            return body;
        }

        var pois = new ArrayList<Map<String, Object>>();
        var around = amapClient.searchAround(
                doubleOr(center.get("longitude"), 0), doubleOr(center.get("latitude"), 0), radius, "", 12);
        if (around.isSuccess()) {
            pois.addAll(around.dataList());
            toolTrace.add(trace("search_around", "ok", "周边 " + radius + " 米内找到 " + pois.size() + " 个地标。"));
        } else if ("skipped".equals(around.status())) {
            warnings.add("高德 Web 服务 Key 未配置，无法查询周边地标。");
        } else {
            toolTrace.add(trace("search_around", "error", str(around.message())));
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("intent", "map_target");
        body.put("queryText", queryText);
        body.put("center", center);
        body.put("radiusMeters", radius);
        body.put("pois", pois);
        body.put("toolTrace", toolTrace);
        body.put("summary", "已定位到「" + str(center.get("label")) + "」，周边 " + pois.size() + " 个地标可参考。");
        body.put("warnings", warnings);
        return body;
    }

    // ------------------------------------------------------------------ 中心点与解析

    /**
     * 中心点解析：显式坐标（center/longitude+latitude）优先，其次从查询文本提取定位关键词走
     * 高德 inputtips/geocode。返回 map 额外携带 locationText 供 parsed 展示（调用方取走后移除）。
     */
    private Map<String, Object> resolveCenter(Map<String, Object> source, String queryText, String city,
                                              List<Map<String, Object>> toolTrace, List<String> warnings) {
        var centerInput = asMap(source.get("center"));
        var longitude = optionalDouble(centerInput.get("longitude"));
        var latitude = optionalDouble(centerInput.get("latitude"));
        if (longitude == null || latitude == null) {
            longitude = optionalDouble(source.get("longitude"));
            latitude = optionalDouble(source.get("latitude"));
        }
        var label = firstNonEmpty(str(centerInput.get("label")), str(source.get("label")));
        if (longitude != null && latitude != null) {
            var center = new LinkedHashMap<String, Object>();
            center.put("longitude", longitude);
            center.put("latitude", latitude);
            center.put("label", firstNonEmpty(label, "地图选点"));
            center.put("city", city);
            center.put("coordinateSystem", "GCJ02");
            center.put("source", "map_click");
            center.put("locationText", firstNonEmpty(label, "地图选点"));
            toolTrace.add(trace("locate_center", "ok", "已使用地图选点「" + center.get("label") + "」为搜索中心。"));
            return center;
        }

        var locationText = extractLocationKeyword(queryText);
        if (locationText.isEmpty()) {
            return null;
        }
        var geocode = placeService.resolveLocationGeocodePayload(Map.of("city", city, "address", locationText));
        if (Boolean.TRUE.equals(geocode.get("ok"))) {
            var location = asMap(geocode.get("location"));
            var center = new LinkedHashMap<String, Object>();
            center.put("longitude", doubleOr(location.get("longitude"), 0));
            center.put("latitude", doubleOr(location.get("latitude"), 0));
            center.put("label", firstNonEmpty(str(location.get("label")), locationText));
            center.put("city", firstNonEmpty(str(location.get("city")), city));
            center.put("district", str(location.get("district")));
            center.put("coordinateSystem", "GCJ02");
            center.put("source", firstNonEmpty(str(location.get("source")), "amap_geocode"));
            center.put("locationText", locationText);
            toolTrace.add(trace("geocode", "ok",
                    "已定位「" + locationText + "」→ " + center.get("label") + "。"));
            return center;
        }
        var code = str(geocode.get("code"));
        toolTrace.add(trace("geocode", "error", firstNonEmpty(str(geocode.get("summary")), code, "定位失败")));
        warnings.add("amap_key_missing".equals(code)
                ? "高德 Web 服务 Key 未配置，无法定位「" + locationText + "」，已按全城搜索。"
                : "未能精确定位「" + locationText + "」，已按全城搜索。");
        return null;
    }

    /** 取第一个不含数字/约束词的 token 作为定位关键词（queryTokens 已剔除“附近/周边/找”等噪声） */
    static String extractLocationKeyword(String queryText) {
        for (var token : queryTokens(queryText)) {
            if (!CONSTRAINT_TOKEN.matcher(token).matches()) {
                return token;
            }
        }
        return "";
    }

    /** 完全无可用信号（无中心点、无定位词、未解析出任何约束）时要求澄清 */
    private boolean needsClarification(String queryText, Map<String, Object> center, Map<String, Object> requirement) {
        if (center != null) {
            return false;
        }
        if (!extractLocationKeyword(queryText).isEmpty()) {
            return false;
        }
        return requirement.get("budgetMax") == null
                && requirement.get("layout") == null
                && requirement.get("rentType") == null
                && requirement.get("commuteLimitMinutes") == null
                && SearchSupport.stringList(requirement.get("preferences")).isEmpty()
                && SearchSupport.stringList(requirement.get("avoidances")).isEmpty();
    }

    // ------------------------------------------------------------------ payload 组装

    private int resolveRadius(Map<String, Object> source, Map<String, Object> settings) {
        var radius = optionalInt(source.get("radiusMeters"));
        if (radius == null) {
            radius = optionalInt(settings.get("defaultRadiusMeters"));
        }
        return radius == null || radius <= 0 ? 2000 : Math.min(radius, 10000);
    }

    private Map<String, Object> parsedPayload(String city, String locationText, int radius, String sort,
                                              Map<String, Object> requirement) {
        var constraints = new LinkedHashMap<String, Object>();
        constraints.put("budgetMax", requirement.get("budgetMax"));
        constraints.put("layout", requirement.get("layout"));
        constraints.put("rentType", requirement.get("rentType"));
        constraints.put("commuteLimitMinutes", requirement.get("commuteLimitMinutes"));
        constraints.put("preferences", requirement.get("preferences"));
        constraints.put("avoidances", requirement.get("avoidances"));

        var parsed = new LinkedHashMap<String, Object>();
        parsed.put("city", city);
        parsed.put("locationText", locationText);
        parsed.put("radiusMeters", radius);
        parsed.put("sort", sort);
        parsed.put("constraints", constraints);
        return parsed;
    }

    private Map<String, Object> baseResult(String queryText, String searchSource, Map<String, Object> parsed,
                                           Map<String, Object> center, int radius,
                                           List<Map<String, Object>> toolTrace, List<String> warnings) {
        var result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("queryText", queryText);
        result.put("source", searchSource);
        result.put("parsed", parsed);
        result.put("center", center);
        result.put("radiusMeters", radius);
        result.put("toolTrace", toolTrace);
        result.put("warnings", warnings);
        return result;
    }

    private List<Map<String, Object>> markers(List<Map<String, Object>> recommendations) {
        var markers = new ArrayList<Map<String, Object>>();
        for (var item : recommendations) {
            var marker = new LinkedHashMap<String, Object>();
            marker.put("id", item.get("id"));
            marker.put("title", firstNonEmpty(str(item.get("community")), str(item.get("title"))));
            marker.put("longitude", item.get("longitude"));
            marker.put("latitude", item.get("latitude"));
            marker.put("rentPrice", item.get("rentPrice"));
            markers.add(marker);
        }
        return markers;
    }

    private String parseSummary(Map<String, Object> requirement) {
        var parts = new ArrayList<String>();
        if (requirement.get("budgetMax") != null) {
            parts.add("预算≤" + requirement.get("budgetMax") + "元");
        }
        if (requirement.get("layout") != null) {
            parts.add("户型" + requirement.get("layout"));
        }
        if (requirement.get("rentType") != null) {
            parts.add(str(requirement.get("rentType")));
        }
        if (requirement.get("commuteLimitMinutes") != null) {
            parts.add("通勤≤" + requirement.get("commuteLimitMinutes") + "分钟");
        }
        var preferences = SearchSupport.stringList(requirement.get("preferences"));
        if (!preferences.isEmpty()) {
            parts.add("偏好：" + String.join("、", preferences));
        }
        return parts.isEmpty() ? "未识别到明确条件，将按综合评分推荐。" : "已解析需求：" + String.join(" / ", parts) + "。";
    }

    private String intentSummary(String city, Map<String, Object> center, int radius, int count) {
        if (count == 0) {
            return "没有找到匹配的房源，可以尝试放大搜索半径或调整预算条件。";
        }
        if (center != null) {
            return "已在「" + str(center.get("label")) + "」周边 " + radius + " 米内为你推荐 " + count + " 套房源，点击卡片查看评分理由。";
        }
        return "已按需求在" + city + "全城为你推荐 " + count + " 套房源，补充位置关键词可获得更精准的周边结果。";
    }

    private Map<String, Object> trace(String tool, String status, String summary) {
        var step = new LinkedHashMap<String, Object>();
        step.put("tool", tool);
        step.put("status", status);
        step.put("summary", summary);
        return step;
    }
}

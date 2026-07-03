package com.renti.agent.modules.search.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.renti.agent.modules.listing.application.ListingPayloadMapper;
import com.renti.agent.modules.listing.application.ListingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.renti.agent.modules.search.application.SearchSupport.DEFAULT_CITY;
import static com.renti.agent.modules.search.application.SearchSupport.asMap;
import static com.renti.agent.modules.search.application.SearchSupport.intOr;
import static com.renti.agent.modules.search.application.SearchSupport.optionalInt;
import static com.renti.agent.modules.search.application.SearchSupport.round2;
import static com.renti.agent.modules.search.application.SearchSupport.str;
import static com.renti.agent.modules.search.application.SearchSupport.stringList;

/**
 * 启发式推荐评分，规则完整移植旧 recommendations.py：
 * 预算 30 / 通勤 25 / 户型 10 / 地铁 20-12-4 / 偏好 15 / 风险 -10。
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    /** 单条推荐结果（listing 为 camelCase payload） */
    public record Recommendation(Map<String, Object> listing, double score,
                                 List<String> reasons, List<String> riskNotes) {
    }

    private final ListingQueryService listingQueryService;
    private final ListingPayloadMapper listingPayloadMapper;
    private final RequirementParseService requirementParseService;

    /** POST /api/recommendations/search：{requirement:{...}} → top3 + markers */
    public Map<String, Object> searchPayload(Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var requirement = normalizeRequirement(asMap(source.get("requirement")));
        var city = str(requirement.get("city")).isEmpty() ? DEFAULT_CITY : str(requirement.get("city"));
        var listings = listingQueryService.findActiveByCity(city).stream()
                .map(listingPayloadMapper::listingPayload)
                .toList();
        var recommendations = rankListings(requirement, listings, 3);

        var cards = new ArrayList<Map<String, Object>>();
        var markers = new ArrayList<Map<String, Object>>();
        for (var item : recommendations) {
            var card = new LinkedHashMap<>(item.listing());
            card.put("score", item.score());
            card.put("reasons", item.reasons());
            card.put("riskNotes", item.riskNotes());
            cards.add(card);

            var marker = new LinkedHashMap<String, Object>();
            marker.put("id", item.listing().get("id"));
            marker.put("title", item.listing().get("community"));
            marker.put("longitude", item.listing().get("longitude"));
            marker.put("latitude", item.listing().get("latitude"));
            marker.put("rentPrice", item.listing().get("rentPrice"));
            markers.add(marker);
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("recommendations", cards);
        body.put("markers", markers);
        body.put("source", "database");
        body.put("empty", cards.isEmpty());
        return body;
    }

    /** 对齐旧 rank_listings：全量评分后按 score 降序取前 limit 条 */
    public List<Recommendation> rankListings(Map<String, Object> requirement,
                                             List<Map<String, Object>> listings, int limit) {
        var scored = new ArrayList<Recommendation>();
        for (var listing : listings) {
            scored.add(buildRecommendation(requirement, listing));
        }
        scored.sort((left, right) -> Double.compare(right.score(), left.score()));
        return scored.subList(0, Math.min(limit, scored.size()));
    }

    /** 对齐旧 _build_recommendation 评分规则 */
    public Recommendation buildRecommendation(Map<String, Object> requirement, Map<String, Object> listing) {
        double score = 0;
        var reasons = new ArrayList<String>();
        int rentPrice = intOr(listing.get("rentPrice"), 0);
        int commuteMinutes = intOr(listing.get("commuteMinutes"), 45);
        int metroDistance = intOr(listing.get("metroDistanceM"), 999);
        var nearestMetro = str(listing.get("nearestMetro"));

        var budgetMax = optionalInt(requirement.get("budgetMax"));
        if (budgetMax != null) {
            if (rentPrice <= budgetMax) {
                score += 30;
                reasons.add("租金 " + rentPrice + " 元符合预算 " + budgetMax + " 元以内");
            } else {
                int overBudget = rentPrice - budgetMax;
                score += Math.max(0, 30 - overBudget / 100.0);
                reasons.add("租金比预算高 " + overBudget + " 元，可作为备选");
            }
        } else {
            score += 15;
            reasons.add("租金 " + rentPrice + " 元，待补充预算后再判断匹配度");
        }

        var commuteLimit = optionalInt(requirement.get("commuteLimitMinutes"));
        if (commuteLimit != null) {
            if (commuteMinutes <= commuteLimit) {
                score += 25;
                reasons.add("通勤约 " + commuteMinutes + " 分钟，满足 " + commuteLimit + " 分钟内要求");
            } else {
                int extra = commuteMinutes - commuteLimit;
                score += Math.max(0, 25 - extra);
                reasons.add("通勤约 " + commuteMinutes + " 分钟，超出要求 " + extra + " 分钟");
            }
        } else {
            score += 10;
            reasons.add("当前估算通勤约 " + commuteMinutes + " 分钟");
        }

        var layout = str(requirement.get("layout"));
        if (!layout.isEmpty()) {
            var actualLayout = str(listing.get("layout"));
            if (layoutMatches(layout, actualLayout)) {
                score += 10;
                reasons.add("户型匹配 " + layout);
            } else {
                score += 2;
                reasons.add("户型为 " + actualLayout + "，与 " + layout + " 不完全一致");
            }
        }

        if (metroDistance <= 500) {
            score += 20;
            reasons.add("距 " + nearestMetro + " 地铁站约 " + metroDistance + " 米");
        } else if (metroDistance <= 1000) {
            score += 12;
            reasons.add("距 " + nearestMetro + " 地铁站约 " + metroDistance + " 米，步行距离可接受");
        } else {
            score += 4;
            reasons.add("距 " + nearestMetro + " 地铁站约 " + metroDistance + " 米，地铁便利度一般");
        }

        var tags = stringList(listing.get("tags"));
        var riskTags = stringList(listing.get("riskTags"));
        var matchedPreferences = new TreeSet<String>(stringList(requirement.get("preferences")));
        matchedPreferences.retainAll(new LinkedHashSet<>(tags));
        if (!matchedPreferences.isEmpty()) {
            score += Math.min(15, matchedPreferences.size() * 7.5);
            reasons.add("匹配偏好：" + String.join("、", matchedPreferences));
        }

        var matchedRisks = new TreeSet<String>(stringList(requirement.get("avoidances")));
        matchedRisks.retainAll(new LinkedHashSet<>(riskTags));
        if (!matchedRisks.isEmpty()) {
            score -= 10;
        } else if (!riskTags.isEmpty()) {
            score -= Math.min(10, riskTags.size() * 4);
        }

        List<String> riskNotes = !matchedRisks.isEmpty() ? List.copyOf(matchedRisks)
                : !riskTags.isEmpty() ? riskTags : List.of("暂无明显风险标签");

        return new Recommendation(listing, round2(score), reasons, riskNotes);
    }

    /** 对齐旧 _layout_matches：首位数字一致或互相包含 */
    public static boolean layoutMatches(String expected, String actual) {
        var expectedDigits = digits(expected);
        var actualDigits = digits(actual);
        if (!expectedDigits.isEmpty() && !actualDigits.isEmpty()) {
            return expectedDigits.charAt(0) == actualDigits.charAt(0);
        }
        return actual.contains(expected) || expected.contains(actual);
    }

    /** 对齐旧 _requirement_from_dict：宽松读入 requirement 字段（camelCase 优先） */
    public Map<String, Object> normalizeRequirement(Map<String, Object> value) {
        var requirement = new LinkedHashMap<String, Object>();
        requirement.put("city", str(value.get("city")).isEmpty() ? DEFAULT_CITY : str(value.get("city")));
        requirement.put("targetPlace", requirementParseService.selectedPlaceFrom(
                value.get("targetPlace") != null ? value.get("targetPlace") : value.get("target_place")));
        requirement.put("budgetMax", optionalInt(first(value, "budgetMax", "budget_max")));
        requirement.put("areaMin", optionalInt(first(value, "areaMin", "area_min")));
        requirement.put("areaMax", optionalInt(first(value, "areaMax", "area_max")));
        requirement.put("rentType", nullableText(first(value, "rentType", "rent_type")));
        requirement.put("layout", nullableText(value.get("layout")));
        requirement.put("floorPreference", nullableText(first(value, "floorPreference", "floor_preference")));
        requirement.put("sort", str(first(value, "sort", "sort")).isEmpty() ? "score_desc" : str(value.get("sort")));
        requirement.put("queryType", str(first(value, "queryType", "query_type")).isEmpty()
                ? "recommendation" : str(first(value, "queryType", "query_type")));
        requirement.put("commuteLimitMinutes", optionalInt(first(value, "commuteLimitMinutes", "commute_limit_minutes")));
        requirement.put("preferences", stringList(value.get("preferences")));
        requirement.put("avoidances", stringList(value.get("avoidances")));
        requirement.put("environmentPreferences", stringList(first(value, "environmentPreferences", "environment_preferences")));
        requirement.put("missingFields", stringList(first(value, "missingFields", "missing_fields")));
        return requirement;
    }

    private static Object first(Map<String, Object> value, String primary, String fallback) {
        return value.get(primary) != null ? value.get(primary) : value.get(fallback);
    }

    private static String nullableText(Object value) {
        var text = str(value).strip();
        return text.isEmpty() ? null : text;
    }

    private static String digits(String value) {
        var builder = new StringBuilder();
        for (char item : str(value).toCharArray()) {
            if (Character.isDigit(item)) {
                builder.append(item);
            }
        }
        return builder.toString();
    }
}

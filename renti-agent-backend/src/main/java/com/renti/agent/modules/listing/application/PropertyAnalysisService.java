package com.renti.agent.modules.listing.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.infrastructure.client.AmapClient;
import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.infrastructure.persistence.repository.ListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 房源深度分析（POST /api/listings/{id}/detail-analysis）：结合高德地理编码、
 * 周边地铁/生活配套 POI、驾车距离，动态补全通勤与周边评估，结果缓存回房源 raw。
 * 行为对齐旧 services/property_analysis.py。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyAnalysisService {

    private static final String CACHE_FIELD = "detailAnalysis";
    private static final double DEFAULT_LNG = 121.4737;
    private static final double DEFAULT_LAT = 31.2304;

    private final ListingRepository listingRepository;
    private final AmapClient amapClient;

    @Transactional
    public Map<String, Object> analyze(String listingId, Map<String, Object> payload) {
        var entity = listingRepository.findById(listingId).orElse(null);
        if (entity == null) {
            return error("not_found", "房源不存在或已被删除。");
        }
        var target = targetFrom(payload == null ? Map.of() : payload, entity);
        var targetKey = targetKey(target);

        var cached = cachedAnalysis(entity, targetKey);
        if (cached != null) {
            return Map.of(
                    "ok", true,
                    "listingId", listingId,
                    "cacheHit", true,
                    "analysis", cached,
                    "detailPatch", detailPatch(entity, cached),
                    "summary", "已使用房源库中的通勤与周边分析缓存。");
        }

        var analysis = buildAnalysis(entity, target);
        cacheAnalysis(entity, analysis);
        listingRepository.save(entity);

        return Map.of(
                "ok", true,
                "listingId", listingId,
                "cacheHit", false,
                "analysis", analysis,
                "detailPatch", detailPatch(entity, analysis),
                "summary", "已通过高德地图服务补全通勤与周边分析，并写入房源库缓存。");
    }

    // ------------------------------------------------------------------ 分析构建

    private Map<String, Object> buildAnalysis(ListingEntity entity, Map<String, Object> target) {
        var warnings = new ArrayList<String>();
        var toolTrace = new ArrayList<Map<String, Object>>();

        var property = propertyPoint(entity);
        if (!hasPoint(property)) {
            var geocode = amapClient.geocode(listingAddress(entity), orDefault(entity.getCity(), "上海"));
            toolTrace.add(trace("amap_geocode", geocode));
            if (geocode.isSuccess() && !geocode.dataMap().isEmpty()) {
                var data = geocode.dataMap();
                property = new LinkedHashMap<>();
                property.put("label", firstNonEmpty(str(data.get("label")), entity.getCommunity(), "房源位置"));
                property.put("longitude", data.get("longitude"));
                property.put("latitude", data.get("latitude"));
                property.put("source", "amap_geocode");
            } else if (!"skipped".equals(geocode.status())) {
                warnings.add("房源坐标补全失败：" + firstNonEmpty(geocode.message(), geocode.status()));
            }
        }
        if (!hasPoint(property)) {
            property = new LinkedHashMap<>();
            property.put("label", orDefault(entity.getCommunity(), "房源位置"));
            property.put("longitude", null);
            property.put("latitude", null);
            property.put("source", "missing");
        }

        var nearestMetro = fallbackMetro(entity);
        var amenities = new ArrayList<Map<String, Object>>();

        if (hasPoint(property)) {
            double lng = toDouble(property.get("longitude"));
            double lat = toDouble(property.get("latitude"));
            var metroResult = amapClient.searchAround(lng, lat, 3000, "地铁站|轨道交通", 8);
            toolTrace.add(trace("amap_place_around_metro", metroResult));
            var nearest = nearestPoi(metroResult, "metro");
            if (nearest != null) {
                nearestMetro = nearest;
            }

            var amenityResult = amapClient.searchAround(lng, lat, 1200, "便利店|超市|商场|菜市场|餐饮", 12);
            toolTrace.add(trace("amap_place_around_amenity", amenityResult));
            amenities.addAll(amenitiesFrom(amenityResult));
        }

        int straightLine;
        Map<String, Object> commute;
        if (hasPoint(property) && hasPoint(target)) {
            double plng = toDouble(property.get("longitude"));
            double plat = toDouble(property.get("latitude"));
            double tlng = toDouble(target.get("longitude"));
            double tlat = toDouble(target.get("latitude"));
            straightLine = (int) Math.round(distanceMeters(plng, plat, tlng, tlat));
            var distanceResult = amapClient.distance(plng, plat, tlng, tlat, "1");
            toolTrace.add(trace("amap_distance_driving", distanceResult));
            commute = commuteFromDistance(distanceResult, straightLine);
        } else {
            commute = fallbackCommute(target, property);
            straightLine = intOr(commute.get("straightLineDistanceMeters"), 0);
            warnings.add("缺少房源或目标点坐标，暂时只能展示已有缓存或静态估算。");
        }

        if (nearestMetro.get("distanceMeters") == null) {
            warnings.add("未从高德地图查询到可用的附近地铁站。");
        }
        if (amenities.isEmpty()) {
            warnings.add("未从高德地图查询到可用的生活配套 POI。");
        }

        var analysis = new LinkedHashMap<String, Object>();
        analysis.put("version", 1);
        analysis.put("status", warnings.isEmpty() ? "ready" : "partial");
        analysis.put("source", "amap");
        analysis.put("computedAt", OffsetDateTime.now().toString());
        analysis.put("targetKey", targetKey(target));
        analysis.put("target", target);
        analysis.put("property", property);
        analysis.put("straightLineDistanceMeters", straightLine);
        analysis.put("commute", commute);
        analysis.put("nearestMetro", nearestMetro);
        analysis.put("amenities", amenities);
        analysis.put("toolTrace", toolTrace);
        analysis.put("warnings", warnings);
        return analysis;
    }

    // ------------------------------------------------------------------ detailPatch（前端合并用）

    @SuppressWarnings("unchecked")
    private Map<String, Object> detailPatch(ListingEntity entity, Map<String, Object> analysis) {
        var target = asMap(analysis.get("target"));
        var property = asMap(analysis.get("property"));
        var commute = asMap(analysis.get("commute"));
        var nearestMetro = asMap(analysis.get("nearestMetro"));
        var amenities = analysis.get("amenities") instanceof List<?> list ? (List<Map<String, Object>>) list : List.<Map<String, Object>>of();
        int straightLine = intOr(analysis.get("straightLineDistanceMeters"), 0);
        Integer commuteMinutes = optionalInt(commute.get("durationMinutes"));
        Integer routeDistance = optionalInt(commute.get("routeDistanceMeters"));

        var commuteRows = new ArrayList<Map<String, Object>>();
        commuteRows.add(row(
                firstNonEmpty(str(nearestMetro.get("label")), "附近地铁待补全"),
                distanceLabel(nearestMetro.get("distanceMeters")),
                "subway",
                nearestMetro.get("distanceMeters") != null ? "primary" : "muted"));
        commuteRows.add(row(
                commuteMinutes != null ? "驾车估算" : "估算通勤",
                commuteMinutes != null ? commuteMinutes + "m" : "待补全",
                "directions_car", "muted"));
        commuteRows.add(row(
                routeDistance != null ? "路线距离" : "直线距离",
                routeDistance != null ? distanceLabel(routeDistance)
                        : (straightLine > 0 ? distanceLabel(straightLine) : "待补全"),
                "near_me", "muted"));

        var mapAmenities = new ArrayList<Map<String, Object>>();
        if (nearestMetro.get("distanceMeters") != null) {
            mapAmenities.add(Map.of(
                    "label", firstNonEmpty(str(nearestMetro.get("label")), "附近地铁"),
                    "type", "metro",
                    "distanceMeters", intOr(nearestMetro.get("distanceMeters"), 0)));
        }
        if (!amenities.isEmpty()) {
            var first = amenities.get(0);
            mapAmenities.add(Map.of(
                    "label", firstNonEmpty(str(first.get("label")), "生活配套"),
                    "type", "convenience",
                    "distanceMeters", intOr(first.get("distanceMeters"), 0)));
        }

        var commuteMap = new LinkedHashMap<String, Object>();
        var targetPoint = new LinkedHashMap<String, Object>();
        targetPoint.put("label", firstNonEmpty(str(target.get("label")), "目标地址"));
        targetPoint.put("longitude", target.get("longitude"));
        targetPoint.put("latitude", target.get("latitude"));
        commuteMap.put("target", targetPoint);
        var propertyPoint = new LinkedHashMap<String, Object>();
        propertyPoint.put("label", firstNonEmpty(str(property.get("label")), entity.getCommunity(), "房源位置"));
        propertyPoint.put("longitude", property.get("longitude"));
        propertyPoint.put("latitude", property.get("latitude"));
        commuteMap.put("property", propertyPoint);
        commuteMap.put("distanceMeters", straightLine);
        commuteMap.put("amenities", mapAmenities);

        var meta = new LinkedHashMap<String, Object>();
        meta.put("status", orDefault(str(analysis.get("status")), "partial"));
        meta.put("source", orDefault(str(analysis.get("source")), "amap"));
        meta.put("computedAt", str(analysis.get("computedAt")));
        meta.put("warnings", analysis.get("warnings"));

        var patch = new LinkedHashMap<String, Object>();
        patch.put("insight", analysisInsight(entity, analysis));
        patch.put("commute", commuteRows);
        patch.put("commuteMap", commuteMap);
        patch.put("analysisMeta", meta);
        return patch;
    }

    private String analysisInsight(ListingEntity entity, Map<String, Object> analysis) {
        var nearestMetro = asMap(analysis.get("nearestMetro"));
        var commute = asMap(analysis.get("commute"));
        var amenities = analysis.get("amenities") instanceof List<?> list ? list : List.of();
        var parts = new ArrayList<String>();
        parts.add("该房源位于 " + orDefault(entity.getCommunity(), "当前小区") + "，通勤与周边数据已按房源坐标动态补全。");
        if (intOr(analysis.get("straightLineDistanceMeters"), 0) > 0) {
            parts.add("到目标点直线约 " + distanceLabel(analysis.get("straightLineDistanceMeters")) + "。");
        }
        if (nearestMetro.get("distanceMeters") != null) {
            parts.add("最近轨交为 " + nearestMetro.get("label") + "，约 " + distanceLabel(nearestMetro.get("distanceMeters")) + "。");
        }
        if (commute.get("durationMinutes") != null) {
            parts.add("高德距离服务给出的驾车估算约 " + commute.get("durationMinutes") + " 分钟。");
        }
        if (!amenities.isEmpty() && amenities.get(0) instanceof Map<?, ?> first) {
            parts.add("最近生活配套为 " + first.get("label") + "，约 " + distanceLabel(first.get("distanceMeters")) + "。");
        }
        return String.join("", parts);
    }

    // ------------------------------------------------------------------ 缓存与目标点

    @SuppressWarnings("unchecked")
    private Map<String, Object> cachedAnalysis(ListingEntity entity, String targetKey) {
        var raw = entity.getRaw();
        if (raw == null || !(raw.get(CACHE_FIELD) instanceof Map<?, ?> cached)) {
            return null;
        }
        var value = (Map<String, Object>) cached;
        if (!targetKey.equals(str(value.get("targetKey"))) || str(value.get("computedAt")).isEmpty()) {
            return null;
        }
        return value;
    }

    private void cacheAnalysis(ListingEntity entity, Map<String, Object> analysis) {
        var raw = entity.getRaw() == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(entity.getRaw());
        raw.put(CACHE_FIELD, analysis);
        entity.setRaw(raw);

        var property = asMap(analysis.get("property"));
        if (hasPoint(property)) {
            entity.setLongitude(toDouble(property.get("longitude")));
            entity.setLatitude(toDouble(property.get("latitude")));
        }
        var nearestMetro = asMap(analysis.get("nearestMetro"));
        if (str(nearestMetro.get("label")).length() > 0 && !str(nearestMetro.get("label")).contains("待补全")) {
            entity.setNearestMetro(str(nearestMetro.get("label")));
        }
        if (nearestMetro.get("distanceMeters") != null) {
            entity.setMetroDistanceM(intOr(nearestMetro.get("distanceMeters"), entity.getMetroDistanceM() == null ? 999 : entity.getMetroDistanceM()));
        }
        var commute = asMap(analysis.get("commute"));
        if (commute.get("durationMinutes") != null) {
            entity.setCommuteMinutes(intOr(commute.get("durationMinutes"), entity.getCommuteMinutes() == null ? 45 : entity.getCommuteMinutes()));
        }
        entity.setUpdatedAt(OffsetDateTime.now());
    }

    private Map<String, Object> targetFrom(Map<String, Object> payload, ListingEntity entity) {
        var source = payload.get("target") instanceof Map<?, ?> map ? asMap(map) : payload;
        var target = new LinkedHashMap<String, Object>();
        target.put("label", firstNonEmpty(str(source.get("label")), "目标地址"));
        target.put("longitude", optionalDouble(source.get("longitude")));
        target.put("latitude", optionalDouble(source.get("latitude")));
        target.put("source", firstNonEmpty(str(source.get("source")), "property_detail"));
        target.put("city", firstNonEmpty(str(source.get("city")), entity.getCity(), "上海"));
        return target;
    }

    private Map<String, Object> propertyPoint(ListingEntity entity) {
        if (entity.getLongitude() == 0.0 && entity.getLatitude() == 0.0) {
            return null;
        }
        // 兜底默认坐标（人民广场）不作为真实房源点
        if (entity.getLongitude() == DEFAULT_LNG && entity.getLatitude() == DEFAULT_LAT) {
            return null;
        }
        var point = new LinkedHashMap<String, Object>();
        point.put("label", firstNonEmpty(entity.getCommunity(), entity.getTitle(), "房源位置"));
        point.put("longitude", entity.getLongitude());
        point.put("latitude", entity.getLatitude());
        point.put("source", "listing");
        return point;
    }

    private String listingAddress(ListingEntity entity) {
        var parts = new StringBuilder();
        for (var value : List.of(orDefault(entity.getCity(), "上海"), orEmpty(entity.getDistrict()),
                orEmpty(entity.getBusinessArea()), orEmpty(entity.getCommunity()))) {
            if (!value.isBlank()) {
                parts.append(value.strip());
            }
        }
        return parts.toString();
    }

    // ------------------------------------------------------------------ POI 解析

    private Map<String, Object> nearestPoi(AmapClient.AmapResult result, String kind) {
        if (!result.isSuccess()) {
            return null;
        }
        var rows = result.dataList().stream()
                .filter(item -> str(item.get("name")).length() > 0 && item.get("distanceM") != null)
                .sorted((a, b) -> Integer.compare(intOr(a.get("distanceM"), 0), intOr(b.get("distanceM"), 0)))
                .toList();
        if (rows.isEmpty()) {
            return null;
        }
        var first = rows.get(0);
        var poi = new LinkedHashMap<String, Object>();
        poi.put("label", str(first.get("name")));
        poi.put("type", kind);
        poi.put("distanceMeters", intOr(first.get("distanceM"), 0));
        poi.put("longitude", first.get("longitude"));
        poi.put("latitude", first.get("latitude"));
        poi.put("source", "amap_place_around");
        return poi;
    }

    private List<Map<String, Object>> amenitiesFrom(AmapClient.AmapResult result) {
        if (!result.isSuccess()) {
            return List.of();
        }
        return result.dataList().stream()
                .filter(item -> str(item.get("name")).length() > 0 && item.get("distanceM") != null)
                .sorted((a, b) -> Integer.compare(intOr(a.get("distanceM"), 0), intOr(b.get("distanceM"), 0)))
                .limit(5)
                .map(item -> {
                    var poi = new LinkedHashMap<String, Object>();
                    poi.put("label", str(item.get("name")));
                    poi.put("type", "convenience");
                    poi.put("distanceMeters", intOr(item.get("distanceM"), 0));
                    poi.put("longitude", item.get("longitude"));
                    poi.put("latitude", item.get("latitude"));
                    poi.put("source", "amap_place_around");
                    return (Map<String, Object>) poi;
                })
                .toList();
    }

    private Map<String, Object> fallbackMetro(ListingEntity entity) {
        var name = orEmpty(entity.getNearestMetro()).strip();
        Integer distance = entity.getMetroDistanceM();
        if (name.isEmpty() || "待补充".equals(name)) {
            name = "附近地铁待补全";
            distance = null;
        }
        var metro = new LinkedHashMap<String, Object>();
        metro.put("label", name);
        metro.put("type", "metro");
        metro.put("distanceMeters", distance);
        metro.put("source", "listing_fallback");
        return metro;
    }

    private Map<String, Object> fallbackCommute(Map<String, Object> target, Map<String, Object> property) {
        Integer direct = null;
        if (hasPoint(target) && hasPoint(property)) {
            direct = (int) Math.round(distanceMeters(
                    toDouble(property.get("longitude")), toDouble(property.get("latitude")),
                    toDouble(target.get("longitude")), toDouble(target.get("latitude"))));
        }
        var commute = new LinkedHashMap<String, Object>();
        commute.put("mode", "fallback");
        commute.put("durationMinutes", durationFromDirect(direct));
        commute.put("routeDistanceMeters", null);
        commute.put("straightLineDistanceMeters", direct == null ? 0 : direct);
        commute.put("source", "local_estimate");
        return commute;
    }

    private Map<String, Object> commuteFromDistance(AmapClient.AmapResult result, int direct) {
        var commute = new LinkedHashMap<String, Object>();
        if (result.isSuccess() && !result.dataMap().isEmpty()) {
            var data = result.dataMap();
            Integer durationSeconds = optionalInt(data.get("durationSeconds"));
            Integer distanceMeters = optionalInt(data.get("distanceM"));
            commute.put("mode", "driving");
            commute.put("durationMinutes", durationSeconds != null && durationSeconds > 0
                    ? Math.max(1, (int) Math.round(durationSeconds / 60.0)) : durationFromDirect(direct));
            commute.put("routeDistanceMeters", distanceMeters);
            commute.put("straightLineDistanceMeters", direct);
            commute.put("source", "amap_distance");
            return commute;
        }
        commute.put("mode", "fallback");
        commute.put("durationMinutes", durationFromDirect(direct));
        commute.put("routeDistanceMeters", null);
        commute.put("straightLineDistanceMeters", direct);
        commute.put("source", "local_estimate");
        return commute;
    }

    // ------------------------------------------------------------------ 工具方法

    private Map<String, Object> trace(String tool, AmapClient.AmapResult result) {
        return Map.of(
                "tool", tool,
                "status", result.status(),
                "summary", firstNonEmpty(result.message(), result.isSuccess() ? "查询成功" : result.status()));
    }

    private Map<String, Object> row(String label, String value, String icon, String tone) {
        return Map.of("label", label, "value", value, "icon", icon, "tone", tone);
    }

    private static double distanceMeters(double lngA, double latA, double lngB, double latB) {
        double radius = 6371000;
        double phiA = Math.toRadians(latA);
        double phiB = Math.toRadians(latB);
        double deltaPhi = Math.toRadians(latB - latA);
        double deltaLambda = Math.toRadians(lngB - lngA);
        double h = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phiA) * Math.cos(phiB) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return radius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    private static Integer durationFromDirect(Integer distanceMeters) {
        if (distanceMeters == null || distanceMeters == 0) {
            return null;
        }
        return Math.max(1, (int) Math.round(distanceMeters / 350.0));
    }

    private static String distanceLabel(Object value) {
        Integer distance = optionalInt(value);
        if (distance == null) {
            return "待补全";
        }
        if (distance >= 1000) {
            return String.format("%.2fkm", distance / 1000.0);
        }
        return distance + "m";
    }

    private static String targetKey(Map<String, Object> target) {
        Double lng = optionalDouble(target.get("longitude"));
        Double lat = optionalDouble(target.get("latitude"));
        if (lng == null || lat == null) {
            return "no-target";
        }
        return String.format("%.5f,%.5f", lng, lat);
    }

    private static boolean hasPoint(Map<String, Object> value) {
        return value != null && optionalDouble(value.get("longitude")) != null && optionalDouble(value.get("latitude")) != null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private static double toDouble(Object value) {
        Double parsed = optionalDouble(value);
        return parsed == null ? 0.0 : parsed;
    }

    private static Double optionalDouble(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer optionalInt(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int intOr(Object value, int fallback) {
        Integer parsed = optionalInt(value);
        return parsed == null ? fallback : parsed;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonEmpty(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Map<String, Object> error(String code, String summary) {
        return Map.of("ok", false, "code", code, "summary", summary);
    }
}

package com.renti.agent.infrastructure.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.common.config.RentiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 高德 Web 服务客户端：geocode / inputtips / regeo / 周边 POI / 距离测算。
 *
 * <p>行为对齐旧 amap_tools.py（AmapClient + AmapToolResponse），Key 缺失返回 skipped，
 * 网络失败返回 error，均不抛异常，由调用方按 toolTrace 语义处理；数据字段转 camelCase。</p>
 */
@Slf4j
@Component
public class AmapClient {

    /** 对齐旧 AmapToolResponse：status ∈ success/empty/error/skipped */
    public record AmapResult(String status, Object data, String message) {

        public static AmapResult skipped() {
            return new AmapResult("skipped", null, "AMAP_WEB_SERVICE_KEY is not configured");
        }

        public static AmapResult error(String message) {
            return new AmapResult("error", null, message);
        }

        public static AmapResult empty(String message) {
            return new AmapResult("empty", null, message);
        }

        public static AmapResult success(Object data) {
            return new AmapResult("success", data, "");
        }

        public boolean isSuccess() {
            return "success".equals(status);
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> dataMap() {
            return data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> dataList() {
            return data instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        }
    }

    private final RentiProperties properties;
    private final HttpClientFactory httpClientFactory;
    private volatile RestClient restClient;

    public AmapClient(RentiProperties properties, HttpClientFactory httpClientFactory) {
        this.properties = properties;
        this.httpClientFactory = httpClientFactory;
    }

    public boolean available() {
        return !apiKey().isBlank();
    }

    /** 地址文本 → 坐标（/v3/geocode/geo） */
    public AmapResult geocode(String address, String city) {
        if (!available()) {
            return AmapResult.skipped();
        }
        Map<String, Object> payload;
        try {
            payload = requestJson("/v3/geocode/geo", Map.of(
                    "address", address == null ? "" : address,
                    "city", city == null ? "" : city));
        } catch (Exception exception) {
            return AmapResult.error(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        if (!"1".equals(text(payload.get("status")))) {
            return AmapResult.error(firstNonEmpty(text(payload.get("info")), "geocode failed"));
        }
        if (!(payload.get("geocodes") instanceof List<?> geocodes) || geocodes.isEmpty()
                || !(geocodes.get(0) instanceof Map<?, ?> first)) {
            return AmapResult.empty("no geocode result");
        }
        var location = parseLocation(((Map<?, ?>) first).get("location"));
        if (location == null) {
            return AmapResult.error("geocode result has invalid location");
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("longitude", location[0]);
        data.put("latitude", location[1]);
        data.put("label", firstNonEmpty(text(((Map<?, ?>) first).get("formatted_address")), address));
        data.put("city", firstNonEmpty(text(((Map<?, ?>) first).get("city")), city == null || city.isBlank() ? "上海" : city));
        data.put("district", text(((Map<?, ?>) first).get("district")));
        data.put("coordinateSystem", "GCJ02");
        data.put("source", "amap_geocode");
        return AmapResult.success(data);
    }

    /** 关键词联想（/v3/assistant/inputtips），data 为提示点列表 */
    public AmapResult inputtips(String keywords, String city, int limit) {
        if (!available()) {
            return AmapResult.skipped();
        }
        Map<String, Object> payload;
        try {
            payload = requestJson("/v3/assistant/inputtips", Map.of(
                    "keywords", keywords == null ? "" : keywords,
                    "city", city == null ? "" : city,
                    "citylimit", city == null || city.isBlank() ? "false" : "true",
                    "datatype", "all"));
        } catch (Exception exception) {
            return AmapResult.error(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        if (!"1".equals(text(payload.get("status")))) {
            return AmapResult.error(firstNonEmpty(text(payload.get("info")), "inputtips failed"));
        }
        var tips = new ArrayList<Map<String, Object>>();
        if (payload.get("tips") instanceof List<?> rows) {
            int bounded = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 25));
            for (var row : rows) {
                if (!(row instanceof Map<?, ?> item) || tips.size() >= bounded) {
                    continue;
                }
                var location = parseLocation(item.get("location"));
                var tip = new LinkedHashMap<String, Object>();
                tip.put("id", text(item.get("id")));
                tip.put("name", text(item.get("name")));
                tip.put("address", text(item.get("address")));
                tip.put("district", text(item.get("district")));
                tip.put("adcode", text(item.get("adcode")));
                tip.put("typecode", text(item.get("typecode")));
                tip.put("longitude", location == null ? null : location[0]);
                tip.put("latitude", location == null ? null : location[1]);
                tip.put("coordinateSystem", "GCJ02");
                tip.put("source", "amap_inputtips");
                tips.add(tip);
            }
        }
        if (tips.isEmpty()) {
            return AmapResult.empty("no inputtips result");
        }
        return AmapResult.success(tips);
    }

    /** 坐标 → 地址（/v3/geocode/regeo） */
    public AmapResult regeo(double longitude, double latitude) {
        if (!available()) {
            return AmapResult.skipped();
        }
        Map<String, Object> payload;
        try {
            payload = requestJson("/v3/geocode/regeo", Map.of(
                    "location", longitude + "," + latitude,
                    "extensions", "base"));
        } catch (Exception exception) {
            return AmapResult.error(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        if (!"1".equals(text(payload.get("status")))) {
            return AmapResult.error(firstNonEmpty(text(payload.get("info")), "regeo failed"));
        }
        var regeocode = payload.get("regeocode") instanceof Map<?, ?> map ? map : Map.of();
        var component = regeocode.get("addressComponent") instanceof Map<?, ?> map ? map : Map.of();
        var data = new LinkedHashMap<String, Object>();
        data.put("label", firstNonEmpty(text(regeocode.get("formatted_address")), "地图选点"));
        data.put("city", firstNonEmpty(text(component.get("city")), "上海"));
        data.put("district", text(component.get("district")));
        data.put("coordinateSystem", "GCJ02");
        data.put("source", "amap_regeo");
        return AmapResult.success(data);
    }

    /** 周边 POI（/v5/place/around），data 为 POI 列表 */
    public AmapResult searchAround(double longitude, double latitude, int radiusMeters,
                                   String keywords, int limit) {
        if (!available()) {
            return AmapResult.skipped();
        }
        Map<String, Object> payload;
        try {
            payload = requestJson("/v5/place/around", Map.of(
                    "keywords", keywords == null || keywords.isBlank() ? "住宅|小区|地铁|商场" : keywords,
                    "location", longitude + "," + latitude,
                    "radius", String.valueOf(radiusMeters),
                    "page_size", String.valueOf(limit <= 0 ? 10 : limit),
                    "show_fields", "business,photos"));
        } catch (Exception exception) {
            return AmapResult.error(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        if (!"1".equals(text(payload.get("status")))) {
            return AmapResult.error(firstNonEmpty(text(payload.get("info")), "around search failed"));
        }
        var pois = new ArrayList<Map<String, Object>>();
        if (payload.get("pois") instanceof List<?> rows) {
            for (var row : rows) {
                if (!(row instanceof Map<?, ?> item)) {
                    continue;
                }
                var location = parseLocation(item.get("location"));
                var poi = new LinkedHashMap<String, Object>();
                poi.put("id", text(item.get("id")));
                poi.put("name", text(item.get("name")));
                poi.put("type", text(item.get("type")));
                poi.put("address", text(item.get("address")));
                poi.put("distanceM", optionalInt(item.get("distance")));
                poi.put("longitude", location == null ? null : location[0]);
                poi.put("latitude", location == null ? null : location[1]);
                pois.add(poi);
            }
        }
        return AmapResult.success(pois);
    }

    /** 两点距离测算（/v3/distance），type=1 驾车 */
    public AmapResult distance(double originLongitude, double originLatitude,
                               double destinationLongitude, double destinationLatitude, String distanceType) {
        if (!available()) {
            return AmapResult.skipped();
        }
        var type = distanceType == null || distanceType.isBlank() ? "1" : distanceType;
        Map<String, Object> payload;
        try {
            payload = requestJson("/v3/distance", Map.of(
                    "origins", originLongitude + "," + originLatitude,
                    "destination", destinationLongitude + "," + destinationLatitude,
                    "type", type));
        } catch (Exception exception) {
            return AmapResult.error(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        if (!"1".equals(text(payload.get("status")))) {
            return AmapResult.error(firstNonEmpty(text(payload.get("info")), "distance failed"));
        }
        if (!(payload.get("results") instanceof List<?> results) || results.isEmpty()
                || !(results.get(0) instanceof Map<?, ?> first)) {
            return AmapResult.empty("no distance result");
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("distanceM", optionalInt(first.get("distance")));
        data.put("durationSeconds", optionalInt(first.get("duration")));
        data.put("originId", text(first.get("origin_id")));
        data.put("destinationId", text(first.get("dest_id")));
        data.put("type", type);
        data.put("source", "amap_distance");
        return AmapResult.success(data);
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestJson(String path, Map<String, String> params) {
        var response = client().get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path(path)
                            .queryParam("key", apiKey())
                            .queryParam("output", "json");
                    params.forEach(builder::queryParam);
                    return builder.build();
                })
                .retrieve()
                .body(Map.class);
        return response == null ? Map.of() : response;
    }

    private RestClient client() {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    var baseUrl = properties.amap() == null || properties.amap().baseUrl() == null
                            || properties.amap().baseUrl().isBlank()
                            ? "https://restapi.amap.com" : properties.amap().baseUrl();
                    restClient = httpClientFactory.create(baseUrl, "", 5.0);
                }
            }
        }
        return restClient;
    }

    private String apiKey() {
        var key = properties.amap() == null ? null : properties.amap().webServiceKey();
        return key == null ? "" : key.strip();
    }

    private static double[] parseLocation(Object value) {
        if (!(value instanceof String location) || !location.contains(",")) {
            return null;
        }
        var parts = location.split(",", 2);
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
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

    /** 高德部分字段可能返回空数组，统一转字符串 */
    private static String text(Object value) {
        if (value == null || value instanceof List<?> list && list.isEmpty()) {
            return "";
        }
        if (value instanceof List<?> list) {
            return String.valueOf(list.get(0));
        }
        return String.valueOf(value);
    }

    private static String firstNonEmpty(String... values) {
        for (var value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }
}

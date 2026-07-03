package com.renti.agent.modules.search.application;

import java.util.LinkedHashMap;
import java.util.Map;

import com.renti.agent.infrastructure.client.AmapClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.renti.agent.modules.search.application.SearchSupport.DEFAULT_CITY;
import static com.renti.agent.modules.search.application.SearchSupport.cleanText;
import static com.renti.agent.modules.search.application.SearchSupport.doubleOr;
import static com.renti.agent.modules.search.application.SearchSupport.optionalDouble;
import static com.renti.agent.modules.search.application.SearchSupport.str;

/**
 * 地图选点归一化与地址定位。
 * 对齐旧 main.py resolve_place_payload/_suggest_radius 与 locations.py resolve_location_geocode_payload。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceService {

    private final AmapClient amapClient;

    /** POST /api/places/resolve：SelectedPlace（含 type → 建议半径） */
    public Map<String, Object> resolvePlacePayload(Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var placeType = str(source.get("type")).isEmpty() ? "custom_point" : str(source.get("type"));
        var place = new LinkedHashMap<String, Object>();
        place.put("name", str(source.get("name")).isEmpty() ? "地图选点" : str(source.get("name")));
        place.put("type", placeType);
        place.put("longitude", doubleOr(source.get("longitude"), 121.4737));
        place.put("latitude", doubleOr(source.get("latitude"), 31.2304));
        place.put("city", str(source.get("city")).isEmpty() ? DEFAULT_CITY : str(source.get("city")));
        place.put("suggestedRadiusM", suggestRadius(placeType));
        return place;
    }

    /** 对齐旧 _suggest_radius：metro 1200 / 商圈|办公|学校 2000 / 其他 1500 */
    public static int suggestRadius(String placeType) {
        return switch (placeType == null ? "" : placeType) {
            case "metro_station" -> 1200;
            case "business_area", "office", "school" -> 2000;
            default -> 1500;
        };
    }

    /** POST /api/locations/geocode：地点文本 → 坐标（inputtips 优先，geocode 兜底） */
    public Map<String, Object> resolveLocationGeocodePayload(Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var city = cleanText(source.get("city"), 40);
        if (city.isEmpty()) {
            city = DEFAULT_CITY;
        }
        var address = cleanText(source.get("address"), 160);
        var community = cleanText(source.get("community"), 120);

        if (address.isEmpty() && community.isEmpty()) {
            return error("address_required", "请先填写小区、楼盘、道路或详细地址。",
                    Map.of("address", "请填写一个可以定位的地址或地标。"));
        }

        var poiQuery = (address.isEmpty() ? community : address).strip();
        var query = buildGeocodeQuery(city, address, community);

        var inputtips = amapClient.inputtips(poiQuery, city, 10);
        var inputtipLocation = firstInputtipLocation(inputtips, city, poiQuery);
        if (inputtipLocation != null) {
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("location", inputtipLocation);
            body.put("summary", "已定位到 " + inputtipLocation.get("label") + "，坐标已填入表单。");
            return body;
        }

        var result = amapClient.geocode(query, city);
        if (result.isSuccess() && !result.dataMap().isEmpty()) {
            var data = result.dataMap();
            if (isImpreciseGeocodeResult(query, data)) {
                return error("location_not_precise",
                        "高德只返回了“" + SearchSupport.firstNonEmpty(str(data.get("label")), city)
                                + "”这类城市级位置，请补充区名、道路、小区名或学校完整名称。",
                        Map.of("address", "当前地点关键词过于模糊，请补充更具体的位置。"));
            }
            var location = new LinkedHashMap<String, Object>();
            location.put("longitude", doubleOr(data.get("longitude"), 0));
            location.put("latitude", doubleOr(data.get("latitude"), 0));
            location.put("label", SearchSupport.firstNonEmpty(str(data.get("label")), query));
            location.put("city", SearchSupport.firstNonEmpty(str(data.get("city")), city));
            location.put("district", str(data.get("district")));
            location.put("coordinateSystem", "GCJ02");
            location.put("source", SearchSupport.firstNonEmpty(str(data.get("source")), "amap_geocode"));
            location.put("query", query);
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("location", location);
            body.put("summary", "已定位到 " + location.get("label") + "，坐标已填入表单。");
            return body;
        }
        if ("skipped".equals(result.status())) {
            return error("amap_key_missing", "高德 Web 服务 Key 未配置，暂时无法自动查询经纬度。", Map.of());
        }
        if ("empty".equals(result.status())) {
            return error("location_not_found", "没有找到“" + query + "”的可用坐标，请补充更完整的地址。",
                    Map.of("address", "请补充区名、道路、小区名或附近地标。"));
        }
        return error("geocode_failed",
                result.message() == null || result.message().isEmpty() ? "地址定位失败，请稍后重试。" : result.message(),
                Map.of());
    }

    /** 对齐旧 _is_imprecise_geocode_result */
    public static boolean isImpreciseGeocodeResult(String query, Map<String, Object> data) {
        var label = str(data.get("label"));
        var district = str(data.get("district"));
        var normalizedQuery = str(query).replace("市", "");
        var governmentWords = new String[]{"人民政府", "市政府", "政府"};
        boolean labelHasGovernment = false;
        boolean queryHasGovernment = false;
        for (var word : governmentWords) {
            labelHasGovernment = labelHasGovernment || label.contains(word);
            queryHasGovernment = queryHasGovernment || normalizedQuery.contains(word);
        }
        if (labelHasGovernment && !queryHasGovernment) {
            return true;
        }
        return district.isEmpty() && ("上海".equals(label) || "上海市".equals(label))
                && !"上海".equals(normalizedQuery) && !"上海市".equals(normalizedQuery);
    }

    private Map<String, Object> firstInputtipLocation(AmapClient.AmapResult response, String city, String query) {
        if (!response.isSuccess()) {
            return null;
        }
        for (var item : response.dataList()) {
            var longitude = optionalDouble(item.get("longitude"));
            var latitude = optionalDouble(item.get("latitude"));
            var name = str(item.get("name")).strip();
            if (longitude == null || latitude == null || name.isEmpty()) {
                continue;
            }
            var location = new LinkedHashMap<String, Object>();
            location.put("longitude", longitude);
            location.put("latitude", latitude);
            location.put("label", name);
            location.put("city", city);
            location.put("district", str(item.get("district")));
            location.put("coordinateSystem", "GCJ02");
            location.put("source", SearchSupport.firstNonEmpty(str(item.get("source")), "amap_inputtips"));
            location.put("query", query);
            location.put("address", str(item.get("address")));
            return location;
        }
        return null;
    }

    private static String buildGeocodeQuery(String city, String address, String community) {
        var target = (address.isEmpty() ? community : address).strip();
        if (city.isEmpty() || target.startsWith(city) || target.startsWith(city + "市")) {
            return target;
        }
        return (city + target).strip();
    }

    private static Map<String, Object> error(String code, String summary, Map<String, Object> fieldErrors) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("code", code);
        body.put("summary", summary);
        body.put("fieldErrors", fieldErrors);
        return body;
    }
}

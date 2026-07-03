package com.renti.agent.modules.search.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import static com.renti.agent.modules.search.application.SearchSupport.DEFAULT_CITY;
import static com.renti.agent.modules.search.application.SearchSupport.doubleOr;
import static com.renti.agent.modules.search.application.SearchSupport.intOr;
import static com.renti.agent.modules.search.application.SearchSupport.str;

/**
 * 需求文本正则解析，规则完整移植旧 requirements.py（响应字段转 camelCase）。
 */
@Service
public class RequirementParseService {

    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("(?:预算|租金)?\\s*(\\d{3,5})\\s*(?:元|块|左右|以内|以下)?");
    private static final Pattern COMMUTE_PATTERN =
            Pattern.compile("(?:通勤|地铁)?\\s*(\\d{1,2})\\s*分钟");

    /** POST /api/requirements/parse：{text, selectedPlace?} → Requirement（camelCase） */
    public Map<String, Object> parsePayload(Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var selectedPlace = selectedPlaceFrom(
                source.get("selectedPlace") != null ? source.get("selectedPlace") : source.get("selected_place"));
        return parseRequirementText(str(source.get("text")), selectedPlace);
    }

    /** 对齐旧 parse_requirement_text；selectedPlace 为空时 city 取默认并记 missingFields */
    public Map<String, Object> parseRequirementText(String text, Map<String, Object> selectedPlace) {
        var budget = parseBudget(text);
        var commute = parseCommute(text);
        var rentType = parseRentType(text);
        var layout = parseLayout(text);
        var preferences = parsePreferences(text);
        var avoidances = parseAvoidances(text);

        var missingFields = new ArrayList<String>();
        if (budget == null) {
            missingFields.add("budgetMax");
        }
        if (selectedPlace == null) {
            missingFields.add("targetPlace");
        }

        var requirement = new LinkedHashMap<String, Object>();
        requirement.put("city", selectedPlace != null ? str(selectedPlace.get("city")) : DEFAULT_CITY);
        requirement.put("targetPlace", selectedPlace);
        requirement.put("budgetMax", budget);
        requirement.put("areaMin", null);
        requirement.put("areaMax", null);
        requirement.put("rentType", rentType);
        requirement.put("layout", layout);
        requirement.put("floorPreference", null);
        requirement.put("sort", "score_desc");
        requirement.put("queryType", "recommendation");
        requirement.put("commuteLimitMinutes", commute);
        requirement.put("preferences", preferences);
        requirement.put("avoidances", avoidances);
        requirement.put("environmentPreferences", List.of());
        requirement.put("missingFields", missingFields);
        return requirement;
    }

    /** 对齐旧 _selected_place_from_dict */
    public Map<String, Object> selectedPlaceFrom(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return null;
        }
        var source = SearchSupport.asMap(value);
        var type = str(source.get("type")).isEmpty() ? "custom_point" : str(source.get("type"));
        var place = new LinkedHashMap<String, Object>();
        place.put("name", str(source.get("name")).isEmpty() ? "地图选点" : str(source.get("name")));
        place.put("type", type);
        place.put("longitude", doubleOr(source.get("longitude"), 121.4737));
        place.put("latitude", doubleOr(source.get("latitude"), 31.2304));
        place.put("city", str(source.get("city")).isEmpty() ? DEFAULT_CITY : str(source.get("city")));
        place.put("suggestedRadiusM", intOr(
                source.get("suggestedRadiusM") != null ? source.get("suggestedRadiusM") : source.get("suggested_radius_m"),
                PlaceService.suggestRadius(type)));
        return place;
    }

    public Integer parseBudget(String text) {
        var matcher = BUDGET_PATTERN.matcher(str(text));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    public Integer parseCommute(String text) {
        var matcher = COMMUTE_PATTERN.matcher(str(text));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    public String parseRentType(String text) {
        var value = str(text);
        if (value.contains("整租")) {
            return "整租";
        }
        if (value.contains("合租")) {
            return "合租";
        }
        return null;
    }

    public String parseLayout(String text) {
        var value = str(text);
        if (value.contains("一居") || value.contains("1室") || value.contains("一室")) {
            return "1室";
        }
        if (value.contains("两居") || value.contains("二居") || value.contains("2室") || value.contains("两室")) {
            return "2室";
        }
        return null;
    }

    public List<String> parsePreferences(String text) {
        var value = str(text);
        var preferences = new ArrayList<String>();
        if (value.contains("吃饭方便") || value.contains("餐饮方便")) {
            preferences.add("吃饭方便");
        }
        if (value.contains("不要太吵") || value.contains("安静")) {
            preferences.add("不要太吵");
        }
        if (value.contains("地铁")) {
            preferences.add("地铁近");
        }
        return preferences;
    }

    public List<String> parseAvoidances(String text) {
        var value = str(text);
        var avoidances = new ArrayList<String>();
        if (value.contains("老小区")) {
            avoidances.add("老小区");
        }
        if (value.contains("中介")) {
            avoidances.add("中介风险");
        }
        return avoidances;
    }
}

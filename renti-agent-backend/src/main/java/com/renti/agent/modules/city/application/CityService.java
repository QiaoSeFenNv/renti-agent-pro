package com.renti.agent.modules.city.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.renti.agent.infrastructure.persistence.entity.CityEntity;
import com.renti.agent.infrastructure.persistence.repository.CityRepository;
import com.renti.agent.modules.city.application.dto.CityView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 城市库查询：对齐旧 services/cities.py + main.py get_cities_payload。
 *
 * <p>旧版只返回热门城市（HOT_CITY_NAMES 26 城，city_kind=prefecture_city），
 * 按 name 升序分页；查询词匹配 name/slug/name_en/province/adcode。</p>
 */
@Service
@RequiredArgsConstructor
public class CityService {

    private static final Set<String> HOT_CITY_NAMES = Set.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "天津", "南京", "苏州",
            "武汉", "西安", "长沙", "郑州", "青岛", "厦门", "福州", "宁波", "合肥", "济南",
            "沈阳", "大连", "昆明", "佛山", "无锡", "东莞");

    private static final String DEFAULT_HERO_BADGE_ICON = "auto_awesome";
    private static final String DEFAULT_HERO_BADGE_TEXT = "AI 模型已更新至 v2.4";

    private final CityRepository cityRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> citiesPayload(String query, Integer page, Integer limit) {
        var cleanQuery = cleanQuery(query);
        int pageSize = bounded(limit, 24, 1, 100);
        int pageNumber = bounded(page, 1, 1, 100_000);

        var matched = cityRepository.findAll().stream()
                .filter(city -> HOT_CITY_NAMES.contains(city.getName()))
                .filter(city -> matches(city, cleanQuery))
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .toList();

        int total = matched.size();
        int fromIndex = Math.min((pageNumber - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        var cities = matched.subList(fromIndex, toIndex).stream().map(this::toView).toList();

        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", "选择您的目标城市");
        payload.put("notice", "全国城市可进入工作台；系统房源库会按城市隔离，自有房源模式不会混入平台房源。");
        payload.put("cities", cities);
        payload.put("total", total);
        payload.put("page", pageNumber);
        payload.put("pageSize", pageSize);
        payload.put("totalPages", Math.max(1, (total + pageSize - 1) / pageSize));
        payload.put("query", cleanQuery);
        payload.put("modeOptions", List.of(
                Map.of(
                        "value", "system_search",
                        "label", "查找平台房源",
                        "description", "适合还没有意向房源的用户，进入后查询系统采集并审核的城市房源库。"),
                Map.of(
                        "value", "user_import",
                        "label", "导入自有房源",
                        "description", "适合已有候选房源链接或数据的用户，只分析自己导入的数据，不展示系统房源。")));
        return payload;
    }

    /** 首页 heroBadge 配置（对齐旧 home_config.py，读环境变量并回退默认值） */
    public Map<String, Object> homeConfigPayload() {
        var icon = normalizedEnv("RENTAI_HERO_BADGE_ICON");
        var text = normalizedEnv("RENTAI_HERO_BADGE_TEXT");
        return Map.of("heroBadge", Map.of(
                "icon", icon.isEmpty() ? DEFAULT_HERO_BADGE_ICON : icon,
                "text", text.isEmpty() ? DEFAULT_HERO_BADGE_TEXT : text));
    }

    private CityView toView(CityEntity city) {
        boolean enabled = city.isEnabled();
        return new CityView(
                city.getName(),
                city.getSlug(),
                orEmpty(city.getNameEn()),
                city.getName(),
                orEmpty(city.getProvince()),
                orEmpty(city.getAdcode()),
                enabled,
                city.getStatus(),
                enabled ? "city" : null,
                city.getDefaultLongitude(),
                city.getDefaultLatitude());
    }

    private boolean matches(CityEntity city, String query) {
        if (query.isEmpty()) {
            return true;
        }
        var needle = query.toLowerCase();
        return contains(city.getName(), needle)
                || contains(city.getSlug(), needle)
                || contains(city.getNameEn(), needle)
                || contains(city.getProvince(), needle)
                || contains(city.getAdcode(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    private String cleanQuery(String value) {
        var normalized = String.join(" ", String.valueOf(value == null ? "" : value).trim().split("\\s+"));
        return normalized.length() > 40 ? normalized.substring(0, 40) : normalized;
    }

    private String normalizedEnv(String name) {
        var value = System.getenv(name);
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("^[\"']|[\"']$", "");
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private int bounded(Integer value, int defaultValue, int minimum, int maximum) {
        int parsed = value == null ? defaultValue : value;
        return Math.max(minimum, Math.min(maximum, parsed));
    }
}

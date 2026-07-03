package com.renti.agent.modules.user.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.renti.agent.infrastructure.persistence.entity.PlatformConfigEntity;
import com.renti.agent.infrastructure.persistence.repository.PlatformConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作台平台配置（platform_config key=workspace）读取与归一化，
 * 对齐旧版 services/platform_config.py 的 normalize_platform_config。
 */
@Service
@RequiredArgsConstructor
public class WorkspaceConfigService {

    public static final String PLATFORM_CONFIG_KEY = "workspace";
    public static final List<Integer> DEFAULT_LISTING_PAGE_SIZE_OPTIONS = List.of(5, 10);
    public static final int DEFAULT_LISTING_PAGE_SIZE = 5;
    public static final int DEFAULT_RADIUS_METERS = 300;

    private static final List<Map<String, Object>> DEFAULT_MODEL_OPTIONS = List.of(
            modelOption("fast", "RentAI Fast", "响应更快，适合先粗筛位置与价格。"),
            modelOption("balanced", "RentAI Balanced", "兼顾通勤、预算、配套，作为默认推荐策略。"),
            modelOption("deep", "RentAI Deep", "更重视解释与风险提示，适合做最终决策。"));

    private final PlatformConfigRepository platformConfigRepository;

    /** 归一化后的平台工作台配置：modelOptions / listingPageSizeOptions / defaultListingPageSize */
    @Transactional(readOnly = true)
    public Map<String, Object> config() {
        var stored = platformConfigRepository.findById(PLATFORM_CONFIG_KEY)
                .map(PlatformConfigEntity::getConfigJson)
                .orElse(Map.of());
        return normalize(stored);
    }

    public Map<String, Object> normalize(Map<String, Object> values) {
        var source = values == null ? Map.<String, Object>of() : values;
        var models = normalizeModelOptions(source.get("modelOptions"));
        var pageSizes = normalizePageSizeOptions(source.get("listingPageSizeOptions"));
        var config = new LinkedHashMap<String, Object>();
        config.put("modelOptions", models);
        config.put("listingPageSizeOptions", pageSizes);
        config.put("defaultListingPageSize", normalizePageSize(source.get("defaultListingPageSize"), pageSizes));
        return config;
    }

    /** 仅启用的模型选项（用户设置接口回显用） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> enabledModelOptions(Map<String, Object> config) {
        var options = (List<Map<String, Object>>) config.get("modelOptions");
        return options.stream()
                .filter(item -> !Boolean.FALSE.equals(item.get("enabled")))
                .map(LinkedHashMap::new)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    public Set<String> enabledModelValues(Map<String, Object> config) {
        var values = new LinkedHashSet<String>();
        for (Map<String, Object> item : enabledModelOptions(config)) {
            values.add(String.valueOf(item.get("value")));
        }
        return values;
    }

    public String defaultModelValue(Map<String, Object> config) {
        return enabledModelValues(config).stream().findFirst().orElse("balanced");
    }

    @SuppressWarnings("unchecked")
    public List<Integer> listingPageSizeOptions(Map<String, Object> config) {
        return (List<Integer>) config.get("listingPageSizeOptions");
    }

    public int defaultListingPageSize(Map<String, Object> config) {
        return (Integer) config.get("defaultListingPageSize");
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> normalizeModelOptions(Object value) {
        if (!(value instanceof List<?> rows)) {
            return copyDefaults();
        }
        var seen = new LinkedHashSet<String>();
        var result = new ArrayList<Map<String, Object>>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> item)) {
                continue;
            }
            var modelValue = slug(item.get("value"));
            if (modelValue.isEmpty() || !seen.add(modelValue)) {
                continue;
            }
            var label = text(item.get("label"), modelValue, 80);
            var description = text(item.get("description"), "", 240);
            var option = new LinkedHashMap<String, Object>();
            option.put("value", modelValue);
            option.put("label", label);
            option.put("description", description);
            option.put("enabled", !Boolean.FALSE.equals(item.get("enabled")));
            result.add(option);
        }
        return result.isEmpty() ? copyDefaults() : result;
    }

    private List<Integer> normalizePageSizeOptions(Object value) {
        if (!(value instanceof List<?> rows)) {
            return new ArrayList<>(DEFAULT_LISTING_PAGE_SIZE_OPTIONS);
        }
        var result = new ArrayList<Integer>();
        for (Object row : rows) {
            var parsed = asInt(row);
            if (parsed != null && DEFAULT_LISTING_PAGE_SIZE_OPTIONS.contains(parsed) && !result.contains(parsed)) {
                result.add(parsed);
            }
        }
        return result.isEmpty() ? new ArrayList<>(DEFAULT_LISTING_PAGE_SIZE_OPTIONS) : result;
    }

    private int normalizePageSize(Object value, List<Integer> options) {
        var parsed = asInt(value);
        if (parsed == null) {
            return options.isEmpty() ? DEFAULT_LISTING_PAGE_SIZE : options.get(0);
        }
        return options.contains(parsed) ? parsed : options.get(0);
    }

    private static Map<String, Object> modelOption(String value, String label, String description) {
        var option = new LinkedHashMap<String, Object>();
        option.put("value", value);
        option.put("label", label);
        option.put("description", description);
        option.put("enabled", true);
        return option;
    }

    private List<Map<String, Object>> copyDefaults() {
        return DEFAULT_MODEL_OPTIONS.stream()
                .map(LinkedHashMap::new)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private String slug(Object value) {
        var raw = String.valueOf(value == null ? "" : value).strip().toLowerCase();
        var builder = new StringBuilder();
        for (char item : raw.toCharArray()) {
            builder.append(Character.isLetterOrDigit(item) || item == '_' || item == '-' ? item : '-');
        }
        var result = builder.toString();
        while (result.contains("--")) {
            result = result.replace("--", "-");
        }
        result = result.replaceAll("^-+|-+$", "");
        return result.length() > 64 ? result.substring(0, 64) : result;
    }

    private String text(Object value, String fallback, int limit) {
        var cleaned = String.valueOf(value == null ? "" : value).strip();
        if (cleaned.isEmpty()) {
            cleaned = fallback;
        }
        return cleaned.length() > limit ? cleaned.substring(0, limit) : cleaned;
    }

    static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

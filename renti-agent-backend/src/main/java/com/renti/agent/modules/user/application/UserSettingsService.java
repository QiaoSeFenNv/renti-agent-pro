package com.renti.agent.modules.user.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.renti.agent.infrastructure.persistence.entity.UserSettingsEntity;
import com.renti.agent.infrastructure.persistence.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户地图工作台设置：默认值与归一化对齐旧版 user_workspace.py 的
 * DEFAULT_USER_SETTINGS / _normalize_settings。
 */
@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private static final Set<String> SORT_OPTIONS = Set.of("score_desc", "price_asc");
    private static final Set<String> MAP_STYLES = Set.of("normal", "light");
    private static final Set<String> ANALYSIS_FOCUS = Set.of("balanced", "commute", "price", "amenities");
    private static final String DEFAULT_SORT = "score_desc";
    private static final String DEFAULT_MAP_STYLE = "normal";
    private static final String DEFAULT_ANALYSIS_FOCUS = "balanced";

    private final UserSettingsRepository userSettingsRepository;
    private final WorkspaceConfigService workspaceConfigService;

    /** 归一化后的当前设置（无记录时返回默认值） */
    @Transactional(readOnly = true)
    public Map<String, Object> getSettings(Long userId) {
        var config = workspaceConfigService.config();
        var stored = userSettingsRepository.findById(userId)
                .map(UserSettingsEntity::getSettings)
                .orElse(Map.of());
        return normalize(stored, config);
    }

    /** GET /api/user/settings 响应 */
    @Transactional(readOnly = true)
    public Map<String, Object> settingsPayload(Long userId) {
        var config = workspaceConfigService.config();
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("settings", getSettings(userId));
        body.put("modelOptions", workspaceConfigService.enabledModelOptions(config));
        body.put("listingPageSizeOptions", workspaceConfigService.listingPageSizeOptions(config));
        body.put("defaultListingPageSize", workspaceConfigService.defaultListingPageSize(config));
        body.put("usesPlatformDefault", true);
        return body;
    }

    /** PUT /api/user/settings：payload 可为 {settings:{...}} 或直接为设置对象 */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateSettings(Long userId, Map<String, Object> payload) {
        var config = workspaceConfigService.config();
        var incoming = payload != null && payload.get("settings") instanceof Map<?, ?> nested
                ? (Map<String, Object>) nested
                : payload == null ? Map.<String, Object>of() : payload;

        var merged = new LinkedHashMap<>(getSettings(userId));
        merged.putAll(incoming);
        var settings = normalize(merged, config);

        var entity = userSettingsRepository.findById(userId).orElseGet(() -> {
            var created = new UserSettingsEntity();
            created.setUserId(userId);
            return created;
        });
        entity.setSettings(settings);
        entity.setUpdatedAt(OffsetDateTime.now());
        userSettingsRepository.save(entity);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("settings", settings);
        body.put("modelOptions", workspaceConfigService.enabledModelOptions(config));
        body.put("listingPageSizeOptions", workspaceConfigService.listingPageSizeOptions(config));
        body.put("defaultListingPageSize", workspaceConfigService.defaultListingPageSize(config));
        body.put("usesPlatformDefault", true);
        body.put("summary", "设置已保存。");
        return body;
    }

    /** 对齐旧版 _normalize_settings */
    public Map<String, Object> normalize(Map<String, Object> values, Map<String, Object> config) {
        var source = values == null ? Map.<String, Object>of() : values;
        var settings = new LinkedHashMap<String, Object>();
        settings.put("modelProfile", choice(source.get("modelProfile"),
                workspaceConfigService.enabledModelValues(config),
                workspaceConfigService.defaultModelValue(config)));
        settings.put("defaultRadiusMeters", radius(source.get("defaultRadiusMeters")));
        settings.put("defaultSort", choice(source.get("defaultSort"), SORT_OPTIONS, DEFAULT_SORT));
        settings.put("mapStyle", choice(source.get("mapStyle"), MAP_STYLES, DEFAULT_MAP_STYLE));
        settings.put("autoOpenResults", truthy(source.get("autoOpenResults"), true));
        settings.put("saveSearchHistory", truthy(source.get("saveSearchHistory"), true));
        settings.put("analysisFocus", choice(source.get("analysisFocus"), ANALYSIS_FOCUS, DEFAULT_ANALYSIS_FOCUS));
        settings.put("listingPageSize", pageSize(source.get("listingPageSize"), config));
        return settings;
    }

    private String choice(Object value, Set<String> allowed, String fallback) {
        var item = value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
        return allowed.contains(item) ? item : fallback;
    }

    /** 旧版仅允许 300 米 */
    private int radius(Object value) {
        var parsed = WorkspaceConfigService.asInt(value);
        return parsed != null && parsed == WorkspaceConfigService.DEFAULT_RADIUS_METERS
                ? parsed : WorkspaceConfigService.DEFAULT_RADIUS_METERS;
    }

    private int pageSize(Object value, Map<String, Object> config) {
        var parsed = WorkspaceConfigService.asInt(value);
        if (parsed == null) {
            return workspaceConfigService.defaultListingPageSize(config);
        }
        return workspaceConfigService.listingPageSizeOptions(config).contains(parsed)
                ? parsed : workspaceConfigService.defaultListingPageSize(config);
    }

    /**
     * 旧版 bool(merged.get(...))：缺省键因先合入 DEFAULT_USER_SETTINGS 而为 true，
     * 显式传 false 则关闭；这里缺省值由调用方指定为 true。
     */
    private boolean truthy(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        var text = String.valueOf(value).strip().toLowerCase();
        return !text.isEmpty() && !"false".equals(text) && !"0".equals(text) && !"no".equals(text);
    }
}

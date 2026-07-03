package com.renti.agent.modules.user.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.infrastructure.persistence.entity.SearchHistoryEntity;
import com.renti.agent.infrastructure.persistence.repository.SearchHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户搜索历史：列表/清空 + record 落库（供 search 模块在 map-intent 等链路调用）。
 * 行为对齐旧版 record_search_history：尊重 saveSearchHistory 设置、每用户保留最近 30 条。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchHistoryService {

    private static final int LIST_LIMIT = 20;
    private static final int KEEP_LIMIT = 30;

    private final SearchHistoryRepository searchHistoryRepository;
    private final UserSettingsService userSettingsService;

    /** GET /api/user/history */
    @Transactional(readOnly = true)
    public Map<String, Object> listPayload(Long userId) {
        var rows = searchHistoryRepository
                .findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, LIST_LIMIT))
                .stream()
                .map(this::toMap)
                .toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("history", rows);
        body.put("total", rows.size());
        return body;
    }

    /** DELETE /api/user/history */
    @Transactional
    public Map<String, Object> clearPayload(Long userId) {
        var removed = searchHistoryRepository.deleteByUserId(userId);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("removed", removed);
        body.put("summary", "已清空 " + removed + " 条搜索历史。");
        return body;
    }

    /**
     * 记录一次搜索（search 模块调用）。用户关闭 saveSearchHistory 时返回 null。
     *
     * @param payload 请求载荷（query/text/source/radiusMeters/modelProfile/analysisFocus...）
     * @param result  响应载荷（queryText/source/center/radiusMeters/total/recommendations/summary...）
     */
    @Transactional
    public Map<String, Object> record(Long userId, Map<String, Object> payload, Map<String, Object> result) {
        var request = payload == null ? Map.<String, Object>of() : payload;
        var response = result == null ? Map.<String, Object>of() : result;
        var settings = userSettingsService.getSettings(userId);
        if (Boolean.FALSE.equals(settings.get("saveSearchHistory"))) {
            return null;
        }

        var center = response.get("center") instanceof Map<?, ?> map ? map : Map.of();
        var entity = new SearchHistoryEntity();
        entity.setUserId(userId);
        entity.setQueryText(firstText(response.get("queryText"), request.get("query"), request.get("text")));
        entity.setSource(firstTextOr("text", response.get("source"), request.get("source")));
        entity.setCenterLabel(firstText(center.get("label")));
        entity.setLongitude(asDouble(center.get("longitude")));
        entity.setLatitude(asDouble(center.get("latitude")));
        entity.setRadiusMeters(firstPositiveInt(300, response.get("radiusMeters"), request.get("radiusMeters")));
        entity.setResultCount(resultCount(response));
        entity.setModelProfile(firstTextOr(
                String.valueOf(settings.getOrDefault("modelProfile", "balanced")),
                request.get("modelProfile")));
        entity.setRequestPayload(replayPayload(request, response, settings, center));
        entity.setSummary(firstText(response.get("summary")));
        searchHistoryRepository.save(entity);
        trimHistory(userId);
        return toMap(entity);
    }

    // ------------------------------------------------------------------ helpers

    /** 与旧版 _history_replay_payload 一致：保存可回放的请求载荷 */
    private Map<String, Object> replayPayload(Map<String, Object> request,
                                              Map<String, Object> response,
                                              Map<String, Object> settings,
                                              Map<?, ?> center) {
        var source = firstTextOr("text", response.get("source"), request.get("source"));
        var replay = new LinkedHashMap<String, Object>();
        replay.put("source", source);
        replay.put("query", firstText(response.get("queryText"), request.get("query")));
        replay.put("modelProfile", firstTextOr(
                String.valueOf(settings.get("modelProfile")), request.get("modelProfile")));
        replay.put("analysisFocus", firstTextOr(
                String.valueOf(settings.get("analysisFocus")), request.get("analysisFocus")));
        var longitude = asDouble(center.get("longitude"));
        var latitude = asDouble(center.get("latitude"));
        if ("map_click".equals(source) && longitude != null && longitude != 0 && latitude != null && latitude != 0) {
            var centerPayload = new LinkedHashMap<String, Object>();
            centerPayload.put("lng", longitude);
            centerPayload.put("lat", latitude);
            replay.put("center", centerPayload);
            replay.put("label", firstTextOr("历史选点", center.get("label")));
        }
        return replay;
    }

    private int resultCount(Map<String, Object> response) {
        var total = WorkspaceConfigService.asInt(response.get("total"));
        if (total != null && total != 0) {
            return total;
        }
        return response.get("recommendations") instanceof List<?> list ? list.size() : 0;
    }

    /** 超出 30 条时删除更早的记录 */
    private void trimHistory(Long userId) {
        var count = searchHistoryRepository.countByUserId(userId);
        if (count <= KEEP_LIMIT) {
            return;
        }
        Pageable beyond = PageRequest.of(1, KEEP_LIMIT);
        var stale = searchHistoryRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, beyond);
        searchHistoryRepository.deleteAll(stale);
    }

    private Map<String, Object> toMap(SearchHistoryEntity entity) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", entity.getId());
        row.put("queryText", entity.getQueryText());
        row.put("source", entity.getSource());
        row.put("centerLabel", entity.getCenterLabel());
        row.put("longitude", entity.getLongitude());
        row.put("latitude", entity.getLatitude());
        row.put("radiusMeters", entity.getRadiusMeters());
        row.put("resultCount", entity.getResultCount());
        row.put("modelProfile", entity.getModelProfile());
        row.put("requestPayload", entity.getRequestPayload());
        row.put("summary", entity.getSummary());
        row.put("createdAt", entity.getCreatedAt());
        return row;
    }

    private String firstText(Object... candidates) {
        return firstTextOr("", candidates);
    }

    private String firstTextOr(String fallback, Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate != null) {
                var text = String.valueOf(candidate).strip();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return fallback;
    }

    private int firstPositiveInt(int fallback, Object... candidates) {
        for (Object candidate : candidates) {
            var parsed = WorkspaceConfigService.asInt(candidate);
            if (parsed != null && parsed != 0) {
                return parsed;
            }
        }
        return fallback;
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

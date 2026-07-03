package com.renti.agent.infrastructure.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.modules.platform.application.IntegrationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Qdrant REST 客户端。point 结构、filter 与端点对齐旧 rag/qdrant_store.py。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QdrantClient {

    private static final List<String> FILTER_KEYS = List.of("city", "status", "provider", "user_id");
    private static final List<String> INDEX_FIELDS = List.of("city", "status", "provider", "user_id");

    private final HttpClientFactory httpClientFactory;
    private final IntegrationSettingsService settings;

    /** URL 与 API key 是否已配置 */
    public boolean isConfigured() {
        var rag = settings.rag();
        return !rag.qdrantUrl().isBlank() && !rag.qdrantApiKey().isBlank();
    }

    public String collectionName() {
        return settings.rag().qdrantCollection();
    }

    /** GET /collections → collection 名称列表 */
    public List<String> listCollections() {
        var response = request(HttpMethod.GET, "/collections", null);
        var collections = asMap(response.get("result")).get("collections");
        var names = new ArrayList<String>();
        if (collections instanceof List<?> rows) {
            for (var row : rows) {
                var name = asMap(row).get("name");
                if (name != null && !String.valueOf(name).isEmpty()) {
                    names.add(String.valueOf(name));
                }
            }
        }
        return names;
    }

    /** GET /collections/{name} → result（points_count/config.params.vectors 等） */
    public Map<String, Object> collectionInfo() {
        var response = request(HttpMethod.GET, "/collections/" + encodedCollection(), null);
        return asMap(response.get("result"));
    }

    /** 确保 collection 存在（Cosine 距离）并建 payload 索引 */
    public void ensureCollection(int vectorSize) {
        if (vectorSize <= 0) {
            throw new IllegalArgumentException("vector size must be positive");
        }
        if (!listCollections().contains(collectionName())) {
            var vectors = new LinkedHashMap<String, Object>();
            vectors.put("size", vectorSize);
            vectors.put("distance", "Cosine");
            request(HttpMethod.PUT, "/collections/" + encodedCollection(), Map.of("vectors", vectors));
        }
        ensurePayloadIndexes();
    }

    /** city/status/provider/user_id keyword 索引（已存在时忽略） */
    public void ensurePayloadIndexes() {
        for (var field : INDEX_FIELDS) {
            try {
                request(HttpMethod.PUT, "/collections/" + encodedCollection() + "/index?wait=true",
                        Map.of("field_name", field, "field_schema", "keyword"));
            } catch (RuntimeException exception) {
                var message = String.valueOf(exception.getMessage()).toLowerCase();
                if (!message.contains("already exists") && !message.contains("wrong input")) {
                    throw exception;
                }
            }
        }
    }

    /** PUT /points?wait=true 批量 upsert；返回写入数量 */
    public int upsertPoints(List<Map<String, Object>> points) {
        if (points == null || points.isEmpty()) {
            return 0;
        }
        var response = request(HttpMethod.PUT,
                "/collections/" + encodedCollection() + "/points?wait=true", Map.of("points", points));
        var status = String.valueOf(response.get("status"));
        if (!"ok".equals(status) && !"accepted".equals(status)) {
            throw new IllegalStateException("qdrant upsert failed: " + status);
        }
        return points.size();
    }

    /** POST /points/search：向量 + 过滤检索 */
    public List<Map<String, Object>> search(List<Double> vector, int limit, Map<String, Object> filters) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("vector", vector);
        payload.put("limit", Math.max(1, Math.min(limit <= 0 ? 8 : limit, 200)));
        payload.put("with_payload", true);
        var filter = qdrantFilter(filters);
        if (!filter.isEmpty()) {
            payload.put("filter", filter);
        }
        var response = request(HttpMethod.POST,
                "/collections/" + encodedCollection() + "/points/search", payload);
        var result = response.get("result");
        var rows = new ArrayList<Map<String, Object>>();
        if (result instanceof List<?> list) {
            for (var row : list) {
                if (row instanceof Map<?, ?>) {
                    rows.add(asMap(row));
                }
            }
        }
        return rows;
    }

    /** POST /points/scroll：分页浏览（返回 points + next_page_offset） */
    public Map<String, Object> scrollPoints(int limit, Map<String, Object> filters, Object offset) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("limit", Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100)));
        payload.put("with_payload", true);
        payload.put("with_vector", false);
        var filter = qdrantFilter(filters);
        if (!filter.isEmpty()) {
            payload.put("filter", filter);
        }
        if (offset != null && !"".equals(offset)) {
            payload.put("offset", offset);
        }
        var response = request(HttpMethod.POST,
                "/collections/" + encodedCollection() + "/points/scroll", payload);
        var result = response.get("result");
        if (result instanceof Map<?, ?>) {
            return asMap(result);
        }
        var fallback = new LinkedHashMap<String, Object>();
        fallback.put("points", List.of());
        fallback.put("next_page_offset", null);
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(HttpMethod method, String path, Map<String, Object> payload) {
        var rag = settings.rag();
        if (!isConfigured()) {
            throw new IllegalStateException("Qdrant 配置不完整。");
        }
        var baseUrl = rag.qdrantUrl().strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        var client = httpClientFactory.create(baseUrl, rag.proxyUrl(), rag.timeoutSeconds());
        try {
            var spec = client.method(method)
                    .uri(path)
                    .header("api-key", rag.qdrantApiKey())
                    .accept(MediaType.APPLICATION_JSON);
            if (payload != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(payload);
            }
            Map<String, Object> body = spec.retrieve().body(Map.class);
            return body == null ? new LinkedHashMap<>() : body;
        } catch (RestClientResponseException exception) {
            var detail = exception.getResponseBodyAsString();
            throw new IllegalStateException("qdrant HTTP %d: %s".formatted(
                    exception.getStatusCode().value(),
                    detail.length() > 300 ? detail.substring(0, 300) : detail));
        }
    }

    /** 对齐旧 _qdrant_filter：city/status/provider/user_id 精确 match */
    static Map<String, Object> qdrantFilter(Map<String, Object> filters) {
        var must = new ArrayList<Map<String, Object>>();
        var source = filters == null ? Map.<String, Object>of() : filters;
        for (var key : FILTER_KEYS) {
            var value = source.get(key);
            if (value == null || "".equals(value)) {
                continue;
            }
            must.add(Map.of("key", key, "match", Map.of("value", value)));
        }
        return must.isEmpty() ? Map.of() : Map.of("must", must);
    }

    private String encodedCollection() {
        return URLEncoder.encode(collectionName(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }
}

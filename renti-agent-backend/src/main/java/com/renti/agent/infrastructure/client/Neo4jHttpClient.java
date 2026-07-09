package com.renti.agent.infrastructure.client;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.modules.platform.application.IntegrationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Neo4j Query API（HTTP transport）客户端，对齐旧 graph/neo4j_store.py 的
 * HttpNeo4jQueryStore：neo4j+s://xxx.databases.neo4j.io 转 https://xxx.databases.neo4j.io，
 * POST /db/{database}/query/v2，body {statement, parameters}，Basic 认证，
 * 支持出站代理与 insecureSkipVerify（信任所有证书）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Neo4jHttpClient {

    private final IntegrationSettingsService settings;
    private final ObjectMapper objectMapper;

    /** URL 与密码（api key）是否已配置 */
    public boolean isConfigured() {
        var neo4j = settings.neo4j();
        return !neo4j.url().isBlank() && !neo4j.password().isBlank();
    }

    /**
     * 执行 Cypher（调用方自行保证只读校验），返回行列表（字段名 → 值）。
     * 代理线路 TLS 握手偶发被重置（Remote host terminated the handshake）：
     * 走代理时失败重试一次，仍失败则直连兜底；HTTP 4xx/5xx 与查询错误不重试。
     *
     * @throws IllegalStateException 未配置或上游失败
     */
    public List<Map<String, Object>> execute(String statement, Map<String, Object> parameters) {
        var neo4j = settings.neo4j();
        if (!isConfigured()) {
            throw new IllegalStateException("Neo4j URL 或 API key 未配置。");
        }
        var endpoint = queryApiUrl(effectiveBaseUrl(neo4j), neo4j.database());
        var proxyPlan = neo4j.proxyUrl().isBlank() ? new boolean[]{false, false} : new boolean[]{true, true, false};
        Exception lastFailure = null;
        for (int attempt = 0; attempt < proxyPlan.length; attempt++) {
            try {
                return executeOnce(neo4j, endpoint, statement, parameters, proxyPlan[attempt]);
            } catch (IllegalStateException exception) {
                throw exception;
            } catch (Exception exception) {
                lastFailure = exception;
                if (attempt < proxyPlan.length - 1) {
                    log.warn("Neo4j 请求失败（第 {} 次，via {}），自动重试：{}", attempt + 1,
                            proxyPlan[attempt] ? "proxy" : "direct",
                            mask(String.valueOf(exception.getMessage()), neo4j.password()));
                }
            }
        }
        throw new IllegalStateException(
                mask(String.valueOf(lastFailure == null ? "unknown" : lastFailure.getMessage()), neo4j.password()),
                lastFailure);
    }

    private List<Map<String, Object>> executeOnce(IntegrationSettingsService.Neo4jSettings neo4j, String endpoint,
                                                  String statement, Map<String, Object> parameters,
                                                  boolean viaProxy) throws Exception {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("statement", statement);
        payload.put("parameters", parameters == null ? Map.of() : parameters);
        var body = objectMapper.writeValueAsString(payload);

        var token = Base64.getEncoder().encodeToString(
                (neo4j.username() + ":" + neo4j.password()).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis((long) (neo4j.timeoutSeconds() * 1000)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        var response = httpClient(neo4j, viaProxy).send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            var detail = response.body() == null ? "" : response.body();
            throw new IllegalStateException("neo4j HTTP %d: %s".formatted(response.statusCode(),
                    mask(detail.length() > 300 ? detail.substring(0, 300) : detail, neo4j.password())));
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        if (response.body() != null && !response.body().isBlank()) {
            parsed = objectMapper.readValue(response.body(), objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        }
        failOnErrors(parsed);
        return rowsFromResponse(parsed);
    }

    private HttpClient httpClient(IntegrationSettingsService.Neo4jSettings neo4j, boolean viaProxy) throws Exception {
        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis((long) (neo4j.timeoutSeconds() * 1000)));
        if (viaProxy && !neo4j.proxyUrl().isBlank()) {
            var proxy = URI.create(neo4j.proxyUrl().strip());
            int port = proxy.getPort() > 0 ? proxy.getPort() : 80;
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), port)));
        }
        if (neo4j.insecureSkipVerify()) {
            builder.sslContext(trustAllContext());
        }
        return builder.build();
    }

    private SSLContext trustAllContext() throws Exception {
        var trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        var context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
        return context;
    }

    /** httpUrl 显式配置时优先（对齐旧 HttpNeo4jQueryStore base_url 逻辑） */
    private String effectiveBaseUrl(IntegrationSettingsService.Neo4jSettings neo4j) {
        var section = settings.rawConfig().get("neo4j");
        if (section instanceof Map<?, ?> map) {
            var httpUrl = map.get("httpUrl");
            if (httpUrl instanceof String text && !text.isBlank()) {
                return text.strip();
            }
        }
        return baseUrl(neo4j);
    }

    /** 对齐旧 _http_base_url：http(s) 原样，neo4j+s/bolt+s 等取 host 拼 https */
    static String baseUrl(IntegrationSettingsService.Neo4jSettings neo4j) {
        var url = neo4j.url().strip();
        var parsed = URI.create(url);
        var scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return url;
        }
        return parsed.getHost() != null ? "https://" + parsed.getHost() : url;
    }

    /** 对齐旧 _query_api_url：追加 /db/{database}/query/v2（已带则原样） */
    static String queryApiUrl(String baseUrl, String database) {
        var clean = baseUrl.strip();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (URI.create(clean).getPath() != null && URI.create(clean).getPath().endsWith("/query/v2")) {
            return clean;
        }
        var db = database == null || database.isBlank() ? "neo4j" : database;
        return "%s/db/%s/query/v2".formatted(clean, db);
    }

    /** Query API v2 返回 {data:{fields,values}}；兼容 records 与旧 tx 端点 results/columns 结构 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rowsFromResponse(Map<String, Object> response) {
        var rows = new ArrayList<Map<String, Object>>();
        if (response.get("data") instanceof Map<?, ?> dataMap) {
            var data = (Map<String, Object>) dataMap;
            if (data.get("fields") instanceof List<?> fields && data.get("values") instanceof List<?> values) {
                for (var value : values) {
                    if (!(value instanceof List<?> row)) {
                        continue;
                    }
                    var item = new LinkedHashMap<String, Object>();
                    for (int index = 0; index < fields.size(); index++) {
                        item.put(String.valueOf(fields.get(index)), index < row.size() ? row.get(index) : null);
                    }
                    rows.add(item);
                }
                return rows;
            }
            if (data.get("records") instanceof List<?> records) {
                for (var record : records) {
                    if (record instanceof Map<?, ?> map) {
                        rows.add(new LinkedHashMap<>((Map<String, Object>) map));
                    }
                }
                return rows;
            }
        }
        if (response.get("results") instanceof List<?> results && !results.isEmpty()
                && results.getFirst() instanceof Map<?, ?> firstMap) {
            var first = (Map<String, Object>) firstMap;
            if (first.get("columns") instanceof List<?> columns && first.get("data") instanceof List<?> records) {
                for (var record : records) {
                    var row = record instanceof Map<?, ?> map ? ((Map<String, Object>) map).get("row") : null;
                    if (!(row instanceof List<?> values)) {
                        continue;
                    }
                    var item = new LinkedHashMap<String, Object>();
                    for (int index = 0; index < columns.size(); index++) {
                        item.put(String.valueOf(columns.get(index)), index < values.size() ? values.get(index) : null);
                    }
                    rows.add(item);
                }
            }
        }
        return rows;
    }

    private void failOnErrors(Map<String, Object> response) {
        if (response.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
            throw new IllegalStateException("neo4j query error: " + errors.getFirst());
        }
    }

    private String mask(String detail, String secret) {
        if (detail == null) {
            return "";
        }
        return secret == null || secret.isBlank() ? detail : detail.replace(secret, "***");
    }
}

package com.renti.agent.modules.graph.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.renti.agent.modules.platform.application.IntegrationSettingsService;
import com.renti.agent.modules.platform.application.SystemIntegrationsConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.boundedDouble;
import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.choice;
import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.text;

/**
 * Neo4j 配置读取/保存。响应结构对齐旧 graph/config.py 的
 * neo4j_config_public_payload 与 listing_graph.py 的 update_neo4j_config_payload。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphConfigService {

    private final SystemIntegrationsConfigService systemIntegrationsConfigService;
    private final IntegrationSettingsService integrationSettingsService;

    /** GET /api/admin/graph/neo4j/config */
    @Transactional(readOnly = true)
    public Map<String, Object> getPayload() {
        var body = publicPayload();
        body.put("summary", "Neo4j 配置已读取。");
        return body;
    }

    /** PUT /api/admin/graph/neo4j/config：保存到配置中心 neo4j 段 */
    @Transactional
    public Map<String, Object> updatePayload(Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var current = systemIntegrationsConfigService.normalizedNeo4jSection();
        var neo4j = new LinkedHashMap<String, Object>(current);
        neo4j.put("url", text(source.get("url"), text(current.get("url"), "")));
        neo4j.put("username", text(source.get("username"), text(current.get("username"), "neo4j")));
        neo4j.put("database", text(source.get("database"), text(current.get("database"), "neo4j")));
        neo4j.put("httpUrl", text(source.get("httpUrl"), ""));
        neo4j.put("proxyUrl", text(source.get("proxyUrl"), ""));
        neo4j.put("transport", choice(source.get("transport"), Set.of("auto", "bolt", "http"), "auto"));
        neo4j.put("caBundle", text(source.get("caBundle"), ""));
        neo4j.put("insecureSkipVerify", Boolean.TRUE.equals(source.get("insecureSkipVerify")));
        neo4j.put("timeoutSeconds", boundedDouble(source.get("timeoutSeconds"), 15.0, 1.0, 120.0));
        neo4j.put("auraInstanceId", text(source.get("auraInstanceId"), ""));
        neo4j.put("auraInstanceName", text(source.get("auraInstanceName"), ""));
        var apiKey = text(source.get("apiKey"), "");
        if (Boolean.TRUE.equals(source.get("clearApiKey"))) {
            neo4j.put("apiKey", "");
        } else if (!apiKey.isEmpty()) {
            neo4j.put("apiKey", apiKey);
        }
        systemIntegrationsConfigService.mergeAndSave(Map.of("neo4j", neo4j));
        log.info("Neo4j config updated");

        var body = publicPayload();
        body.put("summary", "Neo4j 配置已保存到数据库。API key 不会在接口中回显。");
        return body;
    }

    /** 数据库配置叠加环境变量兜底后的 neo4j 生效视图 */
    @Transactional(readOnly = true)
    public Map<String, Object> effectiveNeo4j() {
        var section = new LinkedHashMap<>(systemIntegrationsConfigService.normalizedNeo4jSection());
        var typed = integrationSettingsService.neo4j();
        fallback(section, "url", typed.url());
        fallback(section, "apiKey", typed.password());
        fallback(section, "username", typed.username());
        fallback(section, "database", typed.database());
        fallback(section, "proxyUrl", typed.proxyUrl());
        return section;
    }

    public boolean configured(Map<String, Object> neo4j) {
        return !text(neo4j.get("url"), "").isEmpty() && !text(neo4j.get("apiKey"), "").isEmpty();
    }

    /** 对齐旧 neo4j_config_public_payload（不含 summary） */
    LinkedHashMap<String, Object> publicPayload() {
        var neo4j = effectiveNeo4j();
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("configured", configured(neo4j));
        body.put("urlConfigured", !text(neo4j.get("url"), "").isEmpty());
        body.put("apiKeyConfigured", !text(neo4j.get("apiKey"), "").isEmpty());
        body.put("username", text(neo4j.get("username"), "neo4j"));
        body.put("database", text(neo4j.get("database"), "neo4j"));
        body.put("auraConfigured", !text(neo4j.get("auraInstanceId"), "").isEmpty()
                || !text(neo4j.get("auraInstanceName"), "").isEmpty());
        body.put("httpConfigured", !text(neo4j.get("httpUrl"), "").isEmpty());
        body.put("proxyConfigured", !text(neo4j.get("proxyUrl"), "").isEmpty());
        body.put("caBundleConfigured", !text(neo4j.get("caBundle"), "").isEmpty());
        body.put("transport", text(neo4j.get("transport"), "auto"));
        body.put("warnings", List.of());
        return body;
    }

    private void fallback(Map<String, Object> section, String key, String value) {
        if (text(section.get(key), "").isEmpty() && value != null && !value.isBlank()) {
            section.put(key, value);
        }
    }
}

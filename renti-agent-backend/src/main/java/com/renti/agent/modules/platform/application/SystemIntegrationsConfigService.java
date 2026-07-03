package com.renti.agent.modules.platform.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统集成配置（platform_config key=system_integrations）的分段归一化、
 * 合并保存与脱敏回显。行为对齐旧 services/platform_config.py 的
 * normalize/_merge/_public_system_integrations_config。
 */
@Service
@RequiredArgsConstructor
public class SystemIntegrationsConfigService {

    /** 敏感字段：PUT 时空值不覆盖，回显时替换为 xxxConfigured 布尔 */
    private static final Set<String> SECRET_KEYS = Set.of(
            "qdrantApiKey", "deepseekApiKey", "embeddingApiKey", "jinaApiKey", "apiKey");
    private static final List<String> RAG_SECRETS = List.of(
            "qdrantApiKey", "deepseekApiKey", "embeddingApiKey", "jinaApiKey");
    private static final Set<String> EMBEDDING_PROVIDERS = Set.of("auto", "local_hash", "deepseek", "openai", "jina");

    public static final String DEFAULT_QDRANT_COLLECTION = "renti_listings_v2";
    public static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_JINA_URL = "https://api.jina.ai/v1";
    public static final String DEFAULT_JINA_MODEL = "jina-embeddings-v3";

    private final IntegrationSettingsService integrationSettingsService;

    /** GET /api/admin/system-integrations/config */
    @Transactional(readOnly = true)
    public Map<String, Object> getPayload() {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.putAll(publicView(normalize(integrationSettingsService.rawConfig())));
        return body;
    }

    /** PUT /api/admin/system-integrations/config */
    @Transactional
    public Map<String, Object> updatePayload(Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var incoming = source.get("config") instanceof Map<?, ?> ? asMap(source.get("config")) : source;
        var saved = mergeAndSave(incoming);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.putAll(publicView(saved));
        body.put("summary", "系统集成配置已保存，敏感字段不会在接口中回显。");
        return body;
    }

    /** 合并保存：敏感 key 空值不覆盖、clearXxx=true 清空；返回归一化后的全量配置 */
    @Transactional
    public Map<String, Object> mergeAndSave(Map<String, Object> incoming) {
        var result = normalize(integrationSettingsService.rawConfig());
        for (var section : List.of("rag", "neo4j", "llm")) {
            if (!(incoming.get(section) instanceof Map<?, ?>)) {
                continue;
            }
            var sectionValues = asMap(incoming.get(section));
            var merged = new LinkedHashMap<>(asMap(result.get(section)));
            for (var entry : sectionValues.entrySet()) {
                var key = String.valueOf(entry.getKey());
                var value = entry.getValue();
                if (key.startsWith("clear") && Boolean.TRUE.equals(value)) {
                    var secretKey = key.substring(5, 6).toLowerCase() + key.substring(6);
                    merged.put(secretKey, "");
                } else if ((value != null && !"".equals(value)) || !SECRET_KEYS.contains(key)) {
                    merged.put(key, value);
                }
            }
            result.put(section, merged);
        }
        var saved = normalize(result);
        integrationSettingsService.saveRawConfig(saved);
        return saved;
    }

    /** 归一化后的 rag 段（其余模块读取运行时值用） */
    @Transactional(readOnly = true)
    public Map<String, Object> normalizedRagSection() {
        return asMap(normalize(integrationSettingsService.rawConfig()).get("rag"));
    }

    /** 归一化后的 neo4j 段 */
    @Transactional(readOnly = true)
    public Map<String, Object> normalizedNeo4jSection() {
        return asMap(normalize(integrationSettingsService.rawConfig()).get("neo4j"));
    }

    /** 对齐旧 normalize_system_integrations_config */
    public Map<String, Object> normalize(Map<String, Object> values) {
        var source = values == null ? Map.<String, Object>of() : values;
        var rag = asMap(source.get("rag"));
        var neo4j = asMap(source.get("neo4j"));
        var llm = asMap(source.get("llm"));

        var ragOut = new LinkedHashMap<String, Object>();
        ragOut.put("qdrantUrl", text(rag.get("qdrantUrl"), ""));
        ragOut.put("qdrantApiKey", text(rag.get("qdrantApiKey"), ""));
        ragOut.put("qdrantCollection", text(rag.get("qdrantCollection"), DEFAULT_QDRANT_COLLECTION));
        ragOut.put("proxyUrl", text(rag.get("proxyUrl"), ""));
        ragOut.put("embeddingProvider", choice(rag.get("embeddingProvider"), EMBEDDING_PROVIDERS, "auto"));
        ragOut.put("deepseekBaseUrl", text(rag.get("deepseekBaseUrl"), DEFAULT_DEEPSEEK_BASE_URL));
        ragOut.put("deepseekEmbeddingModel", text(rag.get("deepseekEmbeddingModel"), ""));
        ragOut.put("deepseekApiKey", text(rag.get("deepseekApiKey"), ""));
        ragOut.put("embeddingBaseUrl", text(rag.get("embeddingBaseUrl"), ""));
        ragOut.put("embeddingModel", text(rag.get("embeddingModel"), ""));
        ragOut.put("embeddingApiKey", text(rag.get("embeddingApiKey"), ""));
        ragOut.put("jinaUrl", text(rag.get("jinaUrl"), DEFAULT_JINA_URL));
        ragOut.put("jinaModel", text(rag.get("jinaModel"), DEFAULT_JINA_MODEL));
        ragOut.put("jinaApiKey", text(rag.get("jinaApiKey"), ""));
        ragOut.put("localEmbeddingDimensions", boundedInt(rag.get("localEmbeddingDimensions"), 384, 32, 4096));
        ragOut.put("mqeEnabled", bool(rag.get("mqeEnabled"), false));
        ragOut.put("mqeQueryCount", boundedInt(rag.get("mqeQueryCount"), 3, 1, 8));
        ragOut.put("hydeEnabled", bool(rag.get("hydeEnabled"), false));
        ragOut.put("candidatePoolMultiplier", boundedInt(rag.get("candidatePoolMultiplier"), 4, 1, 10));
        ragOut.put("queryExpansionProvider", choice(rag.get("queryExpansionProvider"), Set.of("local", "llm"), "local"));
        ragOut.put("llmRerankEnabled", bool(rag.get("llmRerankEnabled"), false));
        ragOut.put("llmRerankTopN", boundedInt(rag.get("llmRerankTopN"), 12, 3, 30));
        ragOut.put("timeoutSeconds", boundedDouble(rag.get("timeoutSeconds"), 15.0, 1.0, 120.0));

        var neo4jOut = new LinkedHashMap<String, Object>();
        neo4jOut.put("url", text(neo4j.get("url"), ""));
        neo4jOut.put("apiKey", text(neo4j.get("apiKey"), ""));
        neo4jOut.put("username", text(neo4j.get("username"), "neo4j"));
        neo4jOut.put("database", text(neo4j.get("database"), "neo4j"));
        neo4jOut.put("httpUrl", text(neo4j.get("httpUrl"), ""));
        neo4jOut.put("proxyUrl", text(neo4j.get("proxyUrl"), ""));
        neo4jOut.put("transport", choice(neo4j.get("transport"), Set.of("auto", "bolt", "http"), "auto"));
        neo4jOut.put("caBundle", text(neo4j.get("caBundle"), ""));
        neo4jOut.put("insecureSkipVerify", bool(neo4j.get("insecureSkipVerify"), false));
        neo4jOut.put("timeoutSeconds", boundedDouble(neo4j.get("timeoutSeconds"), 15.0, 1.0, 120.0));
        neo4jOut.put("auraInstanceId", text(neo4j.get("auraInstanceId"), ""));
        neo4jOut.put("auraInstanceName", text(neo4j.get("auraInstanceName"), ""));

        var llmOut = new LinkedHashMap<String, Object>();
        llmOut.put("deepseekBaseUrl", text(llm.get("deepseekBaseUrl"), DEFAULT_DEEPSEEK_BASE_URL));
        llmOut.put("deepseekApiKey", text(llm.get("deepseekApiKey"), ""));
        llmOut.put("deepseekChatModel", text(llm.get("deepseekChatModel"), "deepseek-chat"));
        llmOut.put("deepseekTimeoutSeconds", boundedDouble(llm.get("deepseekTimeoutSeconds"), 12.0, 1.0, 120.0));

        var result = new LinkedHashMap<String, Object>();
        result.put("rag", ragOut);
        result.put("neo4j", neo4jOut);
        result.put("llm", llmOut);
        return result;
    }

    /** 对齐旧 _public_system_integrations_config：apiKey → xxxConfigured 布尔 */
    public Map<String, Object> publicView(Map<String, Object> config) {
        var safe = normalize(config);
        var rag = new LinkedHashMap<>(asMap(safe.get("rag")));
        var neo4j = new LinkedHashMap<>(asMap(safe.get("neo4j")));
        var llm = new LinkedHashMap<>(asMap(safe.get("llm")));
        maskSecrets(rag, RAG_SECRETS);
        maskSecrets(neo4j, List.of("apiKey"));
        maskSecrets(llm, List.of("deepseekApiKey"));
        var result = new LinkedHashMap<String, Object>();
        result.put("rag", rag);
        result.put("neo4j", neo4j);
        result.put("llm", llm);
        return result;
    }

    private void maskSecrets(Map<String, Object> section, List<String> keys) {
        for (var key : keys) {
            var configured = section.get(key) != null && !String.valueOf(section.get(key)).isBlank();
            section.remove(key);
            section.put(key + "Configured", configured);
        }
    }

    // ------------------------------------------------------------------ 归一化工具

    public static String text(Object value, String fallback) {
        var cleaned = value == null ? "" : String.valueOf(value).strip();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    public static String choice(Object value, Set<String> allowed, String fallback) {
        var cleaned = value == null ? "" : String.valueOf(value).strip().toLowerCase();
        return allowed.contains(cleaned) ? cleaned : fallback;
    }

    public static boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return Set.of("1", "true", "yes", "on").contains(String.valueOf(value).strip().toLowerCase());
    }

    public static int boundedInt(Object value, int fallback, int minimum, int maximum) {
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = (int) Double.parseDouble(String.valueOf(value).strip());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    public static double boundedDouble(Object value, double fallback, double minimum, double maximum) {
        double parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        } else if (value != null) {
            try {
                parsed = Double.parseDouble(String.valueOf(value).strip());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }
}

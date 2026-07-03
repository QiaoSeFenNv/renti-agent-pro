package com.renti.agent.modules.platform.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.renti.agent.common.config.RentiProperties;
import com.renti.agent.infrastructure.persistence.entity.PlatformConfigEntity;
import com.renti.agent.infrastructure.persistence.repository.PlatformConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 集成配置中心：system_integrations 配置的读取/保存，数据库优先、环境变量兜底。
 *
 * <p>所有外部客户端（DeepSeek/Jina/Qdrant/Neo4j）在每次调用前从这里取运行时配置，
 * 管理端保存后调用 {@link #invalidate()} 即刻生效，无需重启。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationSettingsService {

    public static final String CONFIG_KEY = "system_integrations";

    private final PlatformConfigRepository platformConfigRepository;
    private final RentiProperties properties;

    private final AtomicReference<Map<String, Object>> cache = new AtomicReference<>();

    /** LLM（DeepSeek）配置 */
    public record LlmSettings(String apiKey, String baseUrl, String chatModel, double timeoutSeconds) {
    }

    /** RAG（Jina/Qdrant/检索策略）配置 */
    public record RagSettings(
            String qdrantUrl, String qdrantApiKey, String qdrantCollection,
            String jinaUrl, String jinaApiKey, String jinaModel,
            String embeddingProvider, int localEmbeddingDimensions,
            int candidatePoolMultiplier, boolean mqeEnabled, int mqeQueryCount, boolean hydeEnabled,
            boolean llmRerankEnabled, int llmRerankTopN, String queryExpansionProvider,
            String proxyUrl, double timeoutSeconds) {
    }

    /** Neo4j 配置 */
    public record Neo4jSettings(
            String url, String username, String password, String database,
            String transport, String proxyUrl, boolean insecureSkipVerify, double timeoutSeconds) {
    }

    /** 原始配置文档（管理端展示/编辑用） */
    @Transactional(readOnly = true)
    public Map<String, Object> rawConfig() {
        var cached = cache.get();
        if (cached != null) {
            return cached;
        }
        Map<String, Object> loaded = platformConfigRepository.findById(CONFIG_KEY)
                .map(PlatformConfigEntity::getConfigJson)
                .orElseGet(LinkedHashMap::new);
        cache.set(loaded);
        return loaded;
    }

    /** 保存整份配置文档并失效缓存 */
    @Transactional
    public void saveRawConfig(Map<String, Object> config) {
        var entity = platformConfigRepository.findById(CONFIG_KEY).orElseGet(() -> {
            var created = new PlatformConfigEntity();
            created.setConfigKey(CONFIG_KEY);
            return created;
        });
        entity.setConfigJson(config);
        entity.setUpdatedAt(OffsetDateTime.now());
        platformConfigRepository.save(entity);
        invalidate();
    }

    public void invalidate() {
        cache.set(null);
    }

    public LlmSettings llm() {
        var section = section("llm");
        return new LlmSettings(
                str(section, "deepseekApiKey", properties.deepseek().apiKey()),
                str(section, "deepseekBaseUrl", properties.deepseek().baseUrl()),
                str(section, "deepseekChatModel", properties.deepseek().chatModel()),
                num(section, "deepseekTimeoutSeconds", 30.0));
    }

    public RagSettings rag() {
        var section = section("rag");
        return new RagSettings(
                str(section, "qdrantUrl", properties.qdrant().url()),
                str(section, "qdrantApiKey", properties.qdrant().apiKey()),
                str(section, "qdrantCollection", properties.qdrant().collection()),
                str(section, "jinaUrl", properties.jina().url()),
                str(section, "jinaApiKey", properties.jina().apiKey()),
                str(section, "jinaModel", properties.jina().model()),
                str(section, "embeddingProvider", "auto"),
                (int) num(section, "localEmbeddingDimensions", 384),
                (int) num(section, "candidatePoolMultiplier", 4),
                bool(section, "mqeEnabled", false),
                (int) num(section, "mqeQueryCount", 3),
                bool(section, "hydeEnabled", false),
                bool(section, "llmRerankEnabled", false),
                (int) num(section, "llmRerankTopN", 12),
                str(section, "queryExpansionProvider", "local"),
                str(section, "proxyUrl", ""),
                num(section, "timeoutSeconds", 15.0));
    }

    public Neo4jSettings neo4j() {
        var section = section("neo4j");
        return new Neo4jSettings(
                str(section, "url", properties.neo4j().uri()),
                str(section, "username", properties.neo4j().username()),
                firstNonBlank(str(section, "apiKey", ""), str(section, "password", ""),
                        properties.neo4j().password()),
                str(section, "database", properties.neo4j().database()),
                str(section, "transport", "http"),
                str(section, "proxyUrl", properties.neo4j().proxyUrl()),
                bool(section, "insecureSkipVerify", properties.neo4j().insecureSkipVerify()),
                num(section, "timeoutSeconds", 15.0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String name) {
        var value = rawConfig().get(name);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String str(Map<String, Object> section, String key, String fallback) {
        var value = section.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return fallback == null ? "" : fallback;
    }

    private double num(Map<String, Object> section, String key, double fallback) {
        if (section.get(key) instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private boolean bool(Map<String, Object> section, String key, boolean fallback) {
        if (section.get(key) instanceof Boolean flag) {
            return flag;
        }
        return fallback;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}

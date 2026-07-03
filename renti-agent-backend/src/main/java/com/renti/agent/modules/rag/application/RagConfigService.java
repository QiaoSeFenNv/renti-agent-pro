package com.renti.agent.modules.rag.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.renti.agent.modules.platform.application.IntegrationSettingsService;
import com.renti.agent.modules.platform.application.SystemIntegrationsConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.boundedInt;
import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.choice;
import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.text;

/**
 * RAG 配置读取/保存与生效状态判定。响应结构对齐旧 rag/config.py 的
 * rag_config_payload / _update_rag_config_in_database。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagConfigService {

    public static final Set<String> ALLOWED_EMBEDDING_PROVIDERS =
            Set.of("auto", "local_hash", "deepseek", "openai", "jina");

    private final SystemIntegrationsConfigService systemIntegrationsConfigService;
    private final IntegrationSettingsService integrationSettingsService;

    /** GET /api/admin/rag/config */
    @Transactional(readOnly = true)
    public Map<String, Object> getPayload() {
        var body = configPayload();
        body.put("summary", "RAG 配置已读取。");
        return body;
    }

    /** PUT /api/admin/rag/config：校验 + 保存到配置中心 rag 段 */
    @Transactional
    public Map<String, Object> updatePayload(Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var provider = String.valueOf(source.get("embeddingProvider") == null
                ? "auto" : source.get("embeddingProvider")).strip().toLowerCase();
        if (!ALLOWED_EMBEDDING_PROVIDERS.contains(provider)) {
            var error = new LinkedHashMap<String, Object>();
            error.put("ok", false);
            error.put("code", "invalid_embedding_provider");
            error.put("summary", "Embedding provider 只支持 auto、local_hash、openai、deepseek 或 jina。");
            return error;
        }

        var current = systemIntegrationsConfigService.normalizedRagSection();
        var rag = new LinkedHashMap<String, Object>(current);
        rag.put("qdrantUrl", text(source.get("qdrantUrl"), text(current.get("qdrantUrl"), "")));
        rag.put("qdrantCollection", text(source.get("qdrantCollection"),
                text(current.get("qdrantCollection"), SystemIntegrationsConfigService.DEFAULT_QDRANT_COLLECTION)));
        rag.put("proxyUrl", text(source.get("proxyUrl"), ""));
        rag.put("embeddingProvider", provider);
        rag.put("deepseekBaseUrl", text(source.get("deepseekBaseUrl"),
                SystemIntegrationsConfigService.DEFAULT_DEEPSEEK_BASE_URL));
        rag.put("deepseekEmbeddingModel", text(source.get("deepseekEmbeddingModel"), ""));
        rag.put("embeddingBaseUrl", text(source.get("embeddingBaseUrl"), ""));
        rag.put("embeddingModel", text(source.get("embeddingModel"), ""));
        rag.put("jinaUrl", text(source.get("jinaUrl"), SystemIntegrationsConfigService.DEFAULT_JINA_URL));
        rag.put("jinaModel", text(source.get("jinaModel"), SystemIntegrationsConfigService.DEFAULT_JINA_MODEL));
        rag.put("localEmbeddingDimensions", boundedInt(source.get("localEmbeddingDimensions"), 384, 32, 4096));
        rag.put("mqeEnabled", Boolean.TRUE.equals(source.get("mqeEnabled")));
        rag.put("mqeQueryCount", boundedInt(source.get("mqeQueryCount"), 3, 1, 8));
        rag.put("hydeEnabled", Boolean.TRUE.equals(source.get("hydeEnabled")));
        rag.put("candidatePoolMultiplier", boundedInt(source.get("candidatePoolMultiplier"), 4, 1, 10));
        rag.put("queryExpansionProvider", choice(source.get("queryExpansionProvider"), Set.of("local", "llm"), "local"));
        rag.put("llmRerankEnabled", Boolean.TRUE.equals(source.get("llmRerankEnabled")));
        rag.put("llmRerankTopN", boundedInt(source.get("llmRerankTopN"), 12, 3, 30));

        // 敏感 key：显式 clearXxx=true 清空，提交非空值覆盖，否则保留现值
        for (var pair : new String[][]{
                {"qdrantApiKey", "clearQdrantApiKey"},
                {"deepseekApiKey", "clearDeepseekApiKey"},
                {"embeddingApiKey", "clearEmbeddingApiKey"},
                {"jinaApiKey", "clearJinaApiKey"}}) {
            var secret = text(source.get(pair[0]), "");
            if (Boolean.TRUE.equals(source.get(pair[1]))) {
                rag.put(pair[0], "");
            } else if (!secret.isEmpty()) {
                rag.put(pair[0], secret);
            }
        }
        systemIntegrationsConfigService.mergeAndSave(Map.of("rag", rag));
        log.info("RAG config updated, provider={}", provider);

        var body = configPayload();
        body.put("summary", "RAG 配置已保存到数据库。API key 不会在接口中回显。");
        return body;
    }

    /** 数据库配置叠加环境变量兜底后的 rag 生效视图 */
    @Transactional(readOnly = true)
    public Map<String, Object> effectiveRag() {
        var section = new LinkedHashMap<>(systemIntegrationsConfigService.normalizedRagSection());
        var typed = integrationSettingsService.rag();
        fallback(section, "qdrantUrl", typed.qdrantUrl());
        fallback(section, "qdrantApiKey", typed.qdrantApiKey());
        fallback(section, "qdrantCollection", typed.qdrantCollection());
        fallback(section, "jinaUrl", typed.jinaUrl());
        fallback(section, "jinaApiKey", typed.jinaApiKey());
        fallback(section, "jinaModel", typed.jinaModel());
        fallback(section, "proxyUrl", typed.proxyUrl());
        return section;
    }

    public boolean qdrantConfigured(Map<String, Object> rag) {
        return !text(rag.get("qdrantUrl"), "").isEmpty() && !text(rag.get("qdrantApiKey"), "").isEmpty();
    }

    /** 对齐旧 RAGConfig.embedding_configured */
    public boolean embeddingConfigured(Map<String, Object> rag) {
        var provider = text(rag.get("embeddingProvider"), "auto");
        var jina = !text(rag.get("jinaApiKey"), "").isEmpty()
                && !text(rag.get("jinaUrl"), "").isEmpty()
                && !text(rag.get("jinaModel"), "").isEmpty();
        var openai = !text(rag.get("embeddingApiKey"), "").isEmpty()
                && !text(rag.get("embeddingModel"), "").isEmpty()
                && !text(rag.get("embeddingBaseUrl"), "").isEmpty();
        var deepseek = !text(rag.get("deepseekApiKey"), "").isEmpty()
                && !text(rag.get("deepseekEmbeddingModel"), "").isEmpty();
        return switch (provider) {
            case "jina" -> jina;
            case "openai" -> openai;
            case "deepseek" -> deepseek;
            default -> jina || openai || deepseek;
        };
    }

    public boolean embeddingAvailable(Map<String, Object> rag) {
        return "local_hash".equals(text(rag.get("embeddingProvider"), "auto")) || embeddingConfigured(rag);
    }

    /** 对齐旧 _indexing_configured（新栈 embedding 有 local_hash 兜底，仍按旧逻辑回显状态） */
    public boolean indexingConfigured(Map<String, Object> rag) {
        return qdrantConfigured(rag) && embeddingAvailable(rag);
    }

    /** 对齐旧 _effective_embedding_provider */
    public String effectiveEmbeddingProvider(Map<String, Object> rag) {
        var provider = text(rag.get("embeddingProvider"), "auto");
        if (Set.of("openai", "deepseek", "jina").contains(provider)) {
            return provider;
        }
        var jina = !text(rag.get("jinaApiKey"), "").isEmpty()
                && !text(rag.get("jinaUrl"), "").isEmpty()
                && !text(rag.get("jinaModel"), "").isEmpty();
        if ("auto".equals(provider) && jina) {
            return "jina";
        }
        var openai = !text(rag.get("embeddingApiKey"), "").isEmpty()
                && !text(rag.get("embeddingBaseUrl"), "").isEmpty()
                && !text(rag.get("embeddingModel"), "").isEmpty();
        if ("auto".equals(provider) && openai) {
            return "openai";
        }
        var deepseek = !text(rag.get("deepseekApiKey"), "").isEmpty()
                && !text(rag.get("deepseekEmbeddingModel"), "").isEmpty();
        if ("auto".equals(provider) && deepseek) {
            return "deepseek";
        }
        if ("local_hash".equals(provider)) {
            return "local_hash";
        }
        return "not_configured";
    }

    /** 对齐旧 rag_config_payload（不含 summary） */
    private LinkedHashMap<String, Object> configPayload() {
        var rag = effectiveRag();
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("embeddingProvider", text(rag.get("embeddingProvider"), "auto"));
        body.put("effectiveEmbeddingProvider", effectiveEmbeddingProvider(rag));
        body.put("deepseekBaseUrl", text(rag.get("deepseekBaseUrl"),
                SystemIntegrationsConfigService.DEFAULT_DEEPSEEK_BASE_URL));
        body.put("deepseekEmbeddingModel", text(rag.get("deepseekEmbeddingModel"), ""));
        body.put("deepseekApiKeyConfigured", !text(rag.get("deepseekApiKey"), "").isEmpty());
        body.put("embeddingBaseUrl", text(rag.get("embeddingBaseUrl"), ""));
        body.put("embeddingModel", text(rag.get("embeddingModel"), ""));
        body.put("embeddingApiKeyConfigured", !text(rag.get("embeddingApiKey"), "").isEmpty());
        body.put("jinaUrl", text(rag.get("jinaUrl"), SystemIntegrationsConfigService.DEFAULT_JINA_URL));
        body.put("jinaModel", text(rag.get("jinaModel"), SystemIntegrationsConfigService.DEFAULT_JINA_MODEL));
        body.put("jinaApiKeyConfigured", !text(rag.get("jinaApiKey"), "").isEmpty());
        body.put("localEmbeddingDimensions", boundedInt(rag.get("localEmbeddingDimensions"), 384, 32, 4096));
        body.put("mqeEnabled", Boolean.TRUE.equals(rag.get("mqeEnabled")));
        body.put("mqeQueryCount", boundedInt(rag.get("mqeQueryCount"), 3, 1, 8));
        body.put("hydeEnabled", Boolean.TRUE.equals(rag.get("hydeEnabled")));
        body.put("candidatePoolMultiplier", boundedInt(rag.get("candidatePoolMultiplier"), 4, 1, 10));
        body.put("queryExpansionProvider", text(rag.get("queryExpansionProvider"), "local"));
        body.put("llmRerankEnabled", Boolean.TRUE.equals(rag.get("llmRerankEnabled")));
        body.put("llmRerankTopN", boundedInt(rag.get("llmRerankTopN"), 12, 3, 30));
        body.put("embeddingConfigured", embeddingConfigured(rag));
        body.put("embeddingAvailable", embeddingAvailable(rag));
        body.put("qdrantUrl", text(rag.get("qdrantUrl"), ""));
        body.put("qdrantCollection", text(rag.get("qdrantCollection"),
                SystemIntegrationsConfigService.DEFAULT_QDRANT_COLLECTION));
        body.put("qdrantApiKeyConfigured", !text(rag.get("qdrantApiKey"), "").isEmpty());
        body.put("proxyUrl", text(rag.get("proxyUrl"), ""));
        body.put("qdrantConfigured", qdrantConfigured(rag));
        return body;
    }

    private void fallback(Map<String, Object> section, String key, String value) {
        if (text(section.get(key), "").isEmpty() && value != null && !value.isBlank()) {
            section.put(key, value);
        }
    }
}

package com.renti.agent.common.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台自定义配置绑定（renti.* 前缀）。
 *
 * <p>集中管理跨域、安全会话、外部集成（高德/DeepSeek/Qdrant/Jina/Neo4j）
 * 以及 Python Agent 服务的连接参数，避免 @Value 分散取值。</p>
 */
@ConfigurationProperties(prefix = "renti")
public record RentiProperties(
        Cors cors,
        Security security,
        Agent agent,
        Amap amap,
        Deepseek deepseek,
        Qdrant qdrant,
        Jina jina,
        Neo4j neo4j
) {

    public record Cors(List<String> allowedOrigins) {
    }

    public record Security(
            boolean cookieSecure,
            long sessionTtlSeconds,
            long adminSessionTtlSeconds,
            String internalToken
    ) {
    }

    public record Agent(String baseUrl, long timeoutSeconds) {
    }

    public record Amap(String webServiceKey, String baseUrl) {
    }

    public record Deepseek(String apiKey, String baseUrl, String chatModel) {
    }

    public record Qdrant(String url, String apiKey, String collection) {
    }

    public record Jina(String url, String apiKey, String model) {
    }

    public record Neo4j(
            String uri,
            String username,
            String password,
            String database,
            String proxyUrl,
            boolean insecureSkipVerify
    ) {
    }
}

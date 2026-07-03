package com.renti.agent.infrastructure.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.renti.agent.modules.platform.application.IntegrationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Jina embedding 客户端（OpenAI 兼容 /embeddings 协议 + Jina task 参数）。
 *
 * <p>embeddingProvider=local_hash、Jina 未配置或远程调用失败时，降级到
 * 与旧 embeddings.py LocalHashEmbeddingClient 完全一致的本地 hash 向量。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JinaEmbeddingClient {

    private static final Pattern ASCII_TOKEN = Pattern.compile("[a-z0-9_]+");
    private static final Pattern CJK_TOKEN = Pattern.compile("[\\u4e00-\\u9fff]{1,4}");

    private final HttpClientFactory httpClientFactory;
    private final IntegrationSettingsService settings;

    /** 查询向量（task=retrieval.query） */
    public List<Double> embedQuery(String text) {
        return embed(text, "retrieval.query");
    }

    /** 文档向量（task=retrieval.passage） */
    public List<Double> embedDocument(String text) {
        return embed(text, "retrieval.passage");
    }

    private List<Double> embed(String text, String task) {
        var cleanText = String.join(" ", (text == null ? "" : text).strip().split("\\s+"));
        if (cleanText.isEmpty()) {
            throw new IllegalArgumentException("embedding text is empty");
        }
        var rag = settings.rag();
        var jinaConfigured = !rag.jinaApiKey().isBlank() && !rag.jinaUrl().isBlank() && !rag.jinaModel().isBlank();
        if ("local_hash".equals(rag.embeddingProvider()) || !jinaConfigured) {
            return localHashEmbedding(cleanText, rag.localEmbeddingDimensions());
        }
        try {
            return remoteEmbedding(cleanText, task, rag);
        } catch (Exception exception) {
            log.warn("Jina embedding failed, falling back to local_hash: {}", exception.getMessage());
            return localHashEmbedding(cleanText, rag.localEmbeddingDimensions());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Double> remoteEmbedding(String text, String task, IntegrationSettingsService.RagSettings rag) {
        var client = httpClientFactory.create(embeddingsUrl(rag.jinaUrl()), rag.proxyUrl(), rag.timeoutSeconds());
        var body = new LinkedHashMap<String, Object>();
        body.put("model", rag.jinaModel());
        body.put("input", List.of(text.length() > 8000 ? text.substring(0, 8000) : text));
        body.put("task", task);

        Map<String, Object> response = client.post()
                .header("Authorization", "Bearer " + rag.jinaApiKey())
                .header("User-Agent", "RentiAgent/1.0")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        var data = response == null ? null : response.get("data");
        var first = data instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        var embedding = first == null ? null : first.get("embedding");
        if (!(embedding instanceof List<?> vector) || vector.isEmpty()) {
            throw new IllegalStateException("embedding response missing vector");
        }
        var result = new ArrayList<Double>(vector.size());
        for (var value : vector) {
            result.add(((Number) value).doubleValue());
        }
        return result;
    }

    /**
     * 本地 hash 向量：精确移植旧 embeddings.py LocalHashEmbeddingClient。
     * 分词 = ASCII 词 + 中文 1-4 字连续段 + 中文 bigram；每 token 取 sha256，
     * 前 4 字节大端取模定位维度，第 5 字节奇偶定符号；最后 L2 归一化并保留 8 位小数。
     */
    public static List<Double> localHashEmbedding(String text, int dimensions) {
        int dims = Math.max(32, Math.min(dimensions <= 0 ? 384 : dimensions, 4096));
        var tokens = tokens(text);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("embedding text is empty");
        }
        var vector = new double[dims];
        for (var token : tokens) {
            byte[] digest = sha256(token);
            long index = ((long) (digest[0] & 0xFF) << 24
                    | (long) (digest[1] & 0xFF) << 16
                    | (long) (digest[2] & 0xFF) << 8
                    | (digest[3] & 0xFF)) % dims;
            double sign = (digest[4] & 0xFF) % 2 == 0 ? 1.0 : -1.0;
            vector[(int) index] += sign;
        }
        double sum = 0.0;
        for (var value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0.0) {
            norm = 1.0;
        }
        var result = new ArrayList<Double>(dims);
        for (var value : vector) {
            result.add(Math.round(value / norm * 1e8) / 1e8);
        }
        return result;
    }

    /** 对齐旧 _tokens：ascii 词 + 中文 1-4 字段 + 中文字符 bigram */
    static List<String> tokens(String text) {
        var clean = (text == null ? "" : text).toLowerCase();
        var result = new ArrayList<String>();
        var asciiMatcher = ASCII_TOKEN.matcher(clean);
        while (asciiMatcher.find()) {
            result.add(asciiMatcher.group());
        }
        var cjkMatcher = CJK_TOKEN.matcher(clean);
        while (cjkMatcher.find()) {
            result.add(cjkMatcher.group());
        }
        var chars = new ArrayList<Character>();
        for (var item : clean.toCharArray()) {
            if (item >= '一' && item <= '鿿') {
                chars.add(item);
            }
        }
        for (int index = 0; index < chars.size() - 1; index++) {
            result.add("" + chars.get(index) + chars.get(index + 1));
        }
        return result;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 对齐旧 _embeddings_url：base 未以 /embeddings 结尾时追加 */
    static String embeddingsUrl(String baseUrl) {
        var clean = (baseUrl == null ? "" : baseUrl).strip();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean.endsWith("/embeddings") ? clean : clean + "/embeddings";
    }
}

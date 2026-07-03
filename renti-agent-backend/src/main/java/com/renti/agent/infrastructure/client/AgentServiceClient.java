package com.renti.agent.infrastructure.client;

import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.renti.agent.common.config.RentiProperties;
import com.renti.agent.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Python Agent 服务客户端（契约 §A）：rental-search / property-insight / property-chat。
 * 调用失败抛 BusinessException.upstream，由桥接层捕获后走降级链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentServiceClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RentiProperties properties;
    private final HttpClientFactory httpClientFactory;

    public Map<String, Object> health() {
        try {
            var body = client().get().uri("/health").retrieve().body(MAP_TYPE);
            return body == null ? Map.of() : body;
        } catch (Exception e) {
            throw BusinessException.upstream("Agent 服务不可用：" + e.getMessage());
        }
    }

    public Map<String, Object> rentalSearch(Map<String, Object> payload) {
        return post("/agent/rental-search", payload);
    }

    public Map<String, Object> propertyInsight(Map<String, Object> payload) {
        return post("/agent/property-insight", payload);
    }

    public Map<String, Object> propertyChat(Map<String, Object> payload) {
        return post("/agent/property-chat", payload);
    }

    private Map<String, Object> post(String path, Map<String, Object> payload) {
        try {
            var body = client().post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(MAP_TYPE);
            if (body == null) {
                throw BusinessException.upstream("Agent 服务返回空响应：" + path);
            }
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Agent 服务调用失败 path={} error={}", path, e.getMessage());
            throw BusinessException.upstream("Agent 服务调用失败：" + e.getMessage());
        }
    }

    private RestClient client() {
        var agent = properties.agent();
        return httpClientFactory.create(agent.baseUrl(), "", agent.timeoutSeconds());
    }
}

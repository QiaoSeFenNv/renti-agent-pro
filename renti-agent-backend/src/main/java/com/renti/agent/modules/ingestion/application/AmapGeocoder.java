package com.renti.agent.modules.ingestion.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.renti.agent.common.config.RentiProperties;
import com.renti.agent.infrastructure.client.HttpClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 高德地理编码（爬虫坐标补全用）：小区地址 → 经纬度，key 缺失或失败时静默降级。
 */
@Slf4j
@Component
public class AmapGeocoder {

    private final RentiProperties properties;
    private final HttpClientFactory httpClientFactory;
    private volatile RestClient restClient;

    public AmapGeocoder(RentiProperties properties, HttpClientFactory httpClientFactory) {
        this.properties = properties;
        this.httpClientFactory = httpClientFactory;
    }

    public record GeocodeResult(double longitude, double latitude, String label, String district) {
    }

    public boolean available() {
        var key = properties.amap() == null ? null : properties.amap().webServiceKey();
        return key != null && !key.isBlank();
    }

    /** 地址 → 坐标；不可用/未命中返回 empty，失败抛 IllegalStateException（含错误消息） */
    @SuppressWarnings("unchecked")
    public Optional<GeocodeResult> geocode(String address, String city) {
        if (!available()) {
            return Optional.empty();
        }
        Map<String, Object> response;
        try {
            response = client().get()
                    .uri(uriBuilder -> uriBuilder.path("/v3/geocode/geo")
                            .queryParam("key", properties.amap().webServiceKey())
                            .queryParam("address", address)
                            .queryParam("city", city)
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("高德地理编码请求失败：" + exception.getMessage(), exception);
        }
        if (response == null || !"1".equals(String.valueOf(response.get("status")))) {
            var info = response == null ? "empty_response" : String.valueOf(response.get("info"));
            throw new IllegalStateException("高德地理编码失败：" + info);
        }
        if (!(response.get("geocodes") instanceof List<?> geocodes) || geocodes.isEmpty()
                || !(geocodes.get(0) instanceof Map<?, ?> geocode)) {
            return Optional.empty();
        }
        var location = String.valueOf(geocode.get("location"));
        if (!location.contains(",")) {
            return Optional.empty();
        }
        try {
            var parts = location.split(",", 2);
            return Optional.of(new GeocodeResult(
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    textValue(geocode.get("formatted_address")),
                    textValue(geocode.get("district"))));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private RestClient client() {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    var baseUrl = properties.amap() == null || properties.amap().baseUrl() == null
                            ? "https://restapi.amap.com" : properties.amap().baseUrl();
                    restClient = httpClientFactory.create(baseUrl, "", 4.0);
                }
            }
        }
        return restClient;
    }

    /** 高德部分字段可能返回空数组，统一转字符串 */
    private static String textValue(Object value) {
        if (value == null || value instanceof List<?> list && list.isEmpty()) {
            return "";
        }
        if (value instanceof List<?> list) {
            return String.valueOf(list.get(0));
        }
        return String.valueOf(value);
    }
}

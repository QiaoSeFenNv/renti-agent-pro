package com.renti.agent.infrastructure.client;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient 工厂：统一超时与可选 HTTP 代理（海外服务经 127.0.0.1:7897 出站）。
 */
@Component
public class HttpClientFactory {

    /**
     * 构建 RestClient。
     *
     * @param baseUrl        目标服务根地址
     * @param proxyUrl       形如 http://127.0.0.1:7897，空串表示直连
     * @param timeoutSeconds 连接与读取超时
     */
    public RestClient create(String baseUrl, String proxyUrl, double timeoutSeconds) {
        var factory = new SimpleClientHttpRequestFactory();
        var timeout = Duration.ofMillis((long) (timeoutSeconds * 1000));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            var uri = java.net.URI.create(proxyUrl.trim());
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), port)));
        }
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}

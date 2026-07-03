package com.renti.agent.modules.ingestion.domain;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 爬虫页面抓取：JDK HttpClient + Cookie 会话，请求头对齐旧 _fetch_html。
 */
public class CrawlerHttpFetcher {

    public static final String CRAWLER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private final HttpClient httpClient;

    public CrawlerHttpFetcher() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(12))
                .cookieHandler(new CookieManager())
                .build();
    }

    /** 抓取 HTML，失败抛 IOException（HTTP 4xx/5xx 也视为失败） */
    public String fetchHtml(String url, String referer) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", CRAWLER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,"
                        + "image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Upgrade-Insecure-Requests", "1")
                .GET();
        if (referer != null && !referer.isEmpty()) {
            builder.header("Referer", referer);
        }
        var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IOException(fetchErrorMessage(response.statusCode()));
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** HTTP 错误信息（对齐旧 _fetch_error_message 的风控提示） */
    public static String fetchErrorMessage(int statusCode) {
        var detail = "HTTP " + statusCode;
        if (statusCode == 403 || statusCode == 429) {
            return detail + "，目标站点拒绝了公开抓取请求，可能触发了验证码或风控。";
        }
        return detail;
    }
}

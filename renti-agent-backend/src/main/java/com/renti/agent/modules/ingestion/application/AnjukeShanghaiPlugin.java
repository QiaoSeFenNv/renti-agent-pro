package com.renti.agent.modules.ingestion.application;

import java.util.List;
import java.util.Map;

import com.renti.agent.modules.ingestion.domain.CrawlerHttpFetcher;
import com.renti.agent.modules.ingestion.domain.CrawlerPageParser;
import org.springframework.stereotype.Component;

/**
 * 安居客上海公开列表插件（对齐旧 ANJUKE_SHANGHAI_PLUGIN + admin_crawl_anjuke_shanghai_payload）。
 * 页间保持 Cookie 会话与 Referer，默认不清理旧房源。
 */
@Component
public class AnjukeShanghaiPlugin extends AbstractShanghaiCrawlerPlugin {

    public static final String DEFAULT_URL = "https://sh.zu.anjuke.com/?from=navigation";

    private CrawlerHttpFetcher fetcher;

    public AnjukeShanghaiPlugin(IngestionService ingestionService, AmapGeocoder amapGeocoder) {
        super(ingestionService, amapGeocoder);
    }

    @Override
    public String id() {
        return "anjuke_shanghai";
    }

    @Override
    public String label() {
        return "安居客上海公开列表";
    }

    @Override
    public String provider() {
        return "anjuke";
    }

    @Override
    public String description() {
        return "采集 sh.zu.anjuke.com 公开出租入口页和分页，只读取公开房源卡片；"
                + "默认不清理旧房源，避免目标站点反爬或分页不完整造成误下架。";
    }

    @Override
    public Map<String, Object> defaultOptions() {
        return Map.of(
                "url", DEFAULT_URL,
                "pages", 1,
                "geocode", true,
                "cleanupMissing", false);
    }

    @Override
    public List<String> capabilities() {
        return List.of("one_click_run", "scheduled_run", "geocode", "cleanup_missing", "pagination", "multi_image");
    }

    @Override
    public Map<String, Object> run(Map<String, Object> options) {
        fetcher = new CrawlerHttpFetcher();
        var normalized = new java.util.LinkedHashMap<>(options);
        normalized.put("url", normalizeBaseUrl(String.valueOf(
                options.getOrDefault("url", options.getOrDefault("baseUrl", DEFAULT_URL)))));
        return crawlAndImport(normalized, false);
    }

    @Override
    protected List<Map<String, Object>> fetchPage(String baseUrl, int page, PageContext context) throws Exception {
        var pageUrl = pageUrl(baseUrl, page);
        var referer = context.referer.isEmpty() ? "https://sh.zu.anjuke.com/" : context.referer;
        var html = (fetcher == null ? new CrawlerHttpFetcher() : fetcher).fetchHtml(pageUrl, referer);
        context.referer = pageUrl;
        return CrawlerPageParser.parseAnjukePage(html, pageUrl);
    }

    @Override
    protected String sourceName() {
        return "anjuke_shanghai";
    }

    @Override
    protected String emptySummary() {
        return "未抓取到可解析的安居客上海房源列表。目标站点可能返回了空页、验证码或结构变化。";
    }

    @Override
    protected String doneSummaryPrefix() {
        return "安居客上海公开列表采集完成";
    }

    /** 分页 URL：/p{n}/（对齐旧 _anjuke_page_url） */
    static String pageUrl(String baseUrl, int page) {
        if (page <= 1) {
            return baseUrl;
        }
        var clean = baseUrl.split("\\?", 2)[0].replaceAll("/+$", "");
        var query = baseUrl.contains("?") ? "?" + baseUrl.split("\\?", 2)[1] : "";
        if (clean.equals("https://sh.zu.anjuke.com") || clean.equals("http://sh.zu.anjuke.com")) {
            return "https://sh.zu.anjuke.com/fangyuan/p" + page + "/";
        }
        clean = clean.replaceAll("/p\\d+$", "");
        return clean + "/p" + page + "/" + query;
    }

    /** 基础 URL 归一（对齐旧 _normalize_anjuke_base_url） */
    static String normalizeBaseUrl(String baseUrl) {
        var value = baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl.trim();
        var clean = value.split("\\?", 2)[0].replaceAll("/+$", "");
        if (clean.equals("https://sh.zu.anjuke.com/fangyuan") || clean.equals("http://sh.zu.anjuke.com/fangyuan")) {
            return DEFAULT_URL;
        }
        return value;
    }
}

package com.renti.agent.modules.ingestion.application;

import java.util.List;
import java.util.Map;

import com.renti.agent.modules.ingestion.domain.CrawlerHttpFetcher;
import com.renti.agent.modules.ingestion.domain.CrawlerPageParser;
import org.springframework.stereotype.Component;

/**
 * 链家上海公开列表插件（对齐旧 LIANJIA_SHANGHAI_PLUGIN + admin_crawl_lianjia_shanghai_payload）。
 */
@Component
public class LianjiaShanghaiPlugin extends AbstractShanghaiCrawlerPlugin {

    public static final String DEFAULT_URL = "https://sh.lianjia.com/zufang/";

    public LianjiaShanghaiPlugin(IngestionService ingestionService, AmapGeocoder amapGeocoder) {
        super(ingestionService, amapGeocoder);
    }

    @Override
    public String id() {
        return "lianjia_shanghai";
    }

    @Override
    public String label() {
        return "链家上海公开列表";
    }

    @Override
    public String provider() {
        return "lianjia";
    }

    @Override
    public String description() {
        return "采集 sh.lianjia.com 公开出租列表页，只读取公开房源卡片，不登录、不绕过验证码。";
    }

    @Override
    public Map<String, Object> defaultOptions() {
        return Map.of(
                "url", DEFAULT_URL,
                "pages", 1,
                "geocode", true,
                "cleanupMissing", true);
    }

    @Override
    public List<String> capabilities() {
        return List.of("one_click_run", "scheduled_run", "geocode", "cleanup_missing", "pagination");
    }

    @Override
    public Map<String, Object> run(Map<String, Object> options) {
        return crawlAndImport(options, true);
    }

    @Override
    protected List<Map<String, Object>> fetchPage(String baseUrl, int page, PageContext context) throws Exception {
        var pageUrl = pageUrl(baseUrl, page);
        var html = new CrawlerHttpFetcher().fetchHtml(pageUrl, "");
        return CrawlerPageParser.parseLianjiaPage(html, pageUrl);
    }

    @Override
    protected String sourceName() {
        return "lianjia_shanghai";
    }

    @Override
    protected String emptySummary() {
        return "未抓取到可解析的上海房源列表。目标站点可能返回了空页、验证码或结构变化。";
    }

    @Override
    protected String doneSummaryPrefix() {
        return "链家上海公开列表采集完成";
    }

    /** 分页 URL：/pg{n}/（对齐旧 _lianjia_page_url） */
    static String pageUrl(String baseUrl, int page) {
        if (page <= 1) {
            return baseUrl;
        }
        var clean = baseUrl.split("\\?", 2)[0].replaceAll("/+$", "");
        var query = baseUrl.contains("?") ? "?" + baseUrl.split("\\?", 2)[1] : "";
        clean = clean.replaceAll("/pg\\d+$", "");
        return clean + "/pg" + page + "/" + query;
    }
}

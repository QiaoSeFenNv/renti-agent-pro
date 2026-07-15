package com.renti.agent.modules.ingestion.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 豆瓣「上海租房」小组 UGC 插件（id=douban_shanghai, provider=douban）：调用 scripts/douban_ingest.cjs
 * 无头渲染豆瓣上海租房小组讨论列表+帖子正文，DeepSeek 结构化抽取（复用小红书那套 LLM 抽取管道），
 * 取回 items 后进程内 {@link IngestionService#importRows} 落候选审核流。
 *
 * <p>豆瓣租房小组以个人直租、无中介房源为主。UGC 质量参差，全部进入人工审核（不自动发布）；
 * 有小区名的房源经高德补坐标。抓取/抽取失败降级为 {ok:false} 并记录 failed 任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DoubanShanghaiPlugin implements CrawlerPlugin {

    static final String BASE_URL = "https://www.douban.com/group/";
    static final String SCRIPT_PATH = "scripts/douban_ingest.cjs";

    private final IngestionService ingestionService;
    private final NodeCrawlerRunner crawlerRunner;
    private final ShanghaiGeocodeEnricher geocodeEnricher;

    private volatile Process currentProcess;
    private volatile boolean stopRequested;

    @Override
    public String id() {
        return "douban_shanghai";
    }

    @Override
    public String label() {
        return "豆瓣上海租房小组";
    }

    @Override
    public String provider() {
        return "douban";
    }

    @Override
    public String city() {
        return "上海";
    }

    @Override
    public String description() {
        return "无头渲染豆瓣上海租房小组讨论列表与帖子正文，DeepSeek 结构化抽取租金/户型/区域（复用小红书 LLM 抽取管道）；"
                + "以个人直租、无中介房源为主，全部进入人工审核。";
    }

    @Override
    public Map<String, Object> defaultOptions() {
        return Map.of(
                "limit", 30,
                "groups", "",
                "cleanupMissing", false);
    }

    @Override
    public List<String> capabilities() {
        return List.of("one_click_run", "scheduled_run", "stoppable", "llm_extract", "no_agent", "review_flow");
    }

    @Override
    public boolean supportsStop() {
        return true;
    }

    @Override
    public boolean stopCurrentRun() {
        var process = currentProcess;
        if (process == null || !process.isAlive()) {
            return false;
        }
        stopRequested = true;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        log.info("[{}] 已请求终止抓取子进程 pid={}", id(), process.pid());
        return true;
    }

    @Override
    public Map<String, Object> run(Map<String, Object> options) {
        var opts = options == null ? Map.<String, Object>of() : options;
        stopRequested = false;

        List<Map<String, Object>> items;
        try {
            items = crawlerRunner.fetchItems(SCRIPT_PATH, provider(), opts, process -> currentProcess = process);
        } catch (Exception exception) {
            var message = stopRequested
                    ? "豆瓣采集已被手动停止。"
                    : "豆瓣采集脚本执行失败：" + String.valueOf(exception.getMessage());
            log.warn("[{}] {}", id(), message);
            var job = ingestionService.recordFailedJob(
                    id(), provider(), "public_listing_page", BASE_URL, city(), message);
            return failResult(job.getId(), message);
        } finally {
            currentProcess = null;
        }
        if (items.isEmpty()) {
            var message = "豆瓣未取回可导入的房源（小组页可能需要登录或返回空列表，稍后重试）。";
            var job = ingestionService.recordFailedJob(
                    id(), provider(), "public_listing_page", BASE_URL, city(), message);
            return failResult(job.getId(), message);
        }
        geocodeEnricher.enrichAll(items, city());

        var importPayload = new LinkedHashMap<String, Object>();
        importPayload.put("items", items);
        importPayload.put("sourceName", id());
        importPayload.put("provider", provider());
        importPayload.put("sourceType", "public_listing_page");
        importPayload.put("jobType", "crawler");
        importPayload.put("city", city());
        importPayload.put("baseUrl", BASE_URL);
        importPayload.put("cleanupMissing", false);
        var result = ingestionService.importRows(importPayload);
        result.put("summary", "豆瓣上海租房采集完成：抓取 " + items.size() + " 条，" + result.get("summary"));
        return result;
    }

    private Map<String, Object> failResult(Long jobId, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("jobId", jobId);
        body.put("totalInput", 0);
        body.put("candidatesCreated", 0);
        body.put("candidatesUpdated", 0);
        body.put("summary", message);
        return body;
    }
}

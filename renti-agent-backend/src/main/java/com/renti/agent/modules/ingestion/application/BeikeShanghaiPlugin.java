package com.renti.agent.modules.ingestion.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 贝壳找房「上海租房」插件（id=beike_shanghai, provider=beike）：调用 scripts/beike_ingest.cjs
 * 用 puppeteer-core 无头渲染 sh.zu.ke.com 列表页，取回 items 后进程内 {@link IngestionService#importRows}
 * 落候选审核流。贝壳与链家共用同一抓取脚本（--provider 区分）。
 *
 * <p>列表页带布尔「官方核验」旗标（存入 listing.raw.gov_certified），真正的核验编号由异步核验器
 * 进详情页取得后反查官方平台。抓取失败降级为 {ok:false} 并记录 failed 任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BeikeShanghaiPlugin implements CrawlerPlugin {

    static final String BASE_URL = "https://sh.zu.ke.com/zufang/";

    private final IngestionService ingestionService;
    private final NodeCrawlerRunner crawlerRunner;
    private final ShanghaiGeocodeEnricher geocodeEnricher;

    private volatile Process currentProcess;
    private volatile boolean stopRequested;

    @Override
    public String id() {
        return "beike_shanghai";
    }

    @Override
    public String label() {
        return "贝壳找房上海租房";
    }

    @Override
    public String provider() {
        return "beike";
    }

    @Override
    public String city() {
        return "上海";
    }

    @Override
    public String description() {
        return "用本机 Chrome 无头渲染 sh.zu.ke.com 公开租房列表页，读取公开房源卡片（含官方核验旗标），"
                + "进入候选审核流；不登录、不绕过验证码。支持按区多入口轮抓缓解区域倒挂。";
    }

    @Override
    public Map<String, Object> defaultOptions() {
        return Map.of(
                "pages", 2,
                "limit", 120,
                "districts", "",
                "cleanupMissing", false);
    }

    @Override
    public List<String> capabilities() {
        return List.of("one_click_run", "scheduled_run", "stoppable", "gov_certification", "review_flow", "pagination");
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
        boolean cleanupMissing = Boolean.TRUE.equals(opts.get("cleanupMissing"));
        stopRequested = false;

        List<Map<String, Object>> items;
        try {
            items = crawlerRunner.fetchItems(provider(), opts, process -> currentProcess = process);
        } catch (Exception exception) {
            var message = stopRequested
                    ? "贝壳采集已被手动停止。"
                    : "贝壳采集脚本执行失败：" + String.valueOf(exception.getMessage());
            log.warn("[{}] {}", id(), message);
            var job = ingestionService.recordFailedJob(
                    id(), provider(), "public_listing_page", BASE_URL, city(), message);
            return failResult(job.getId(), message);
        } finally {
            currentProcess = null;
        }
        if (items.isEmpty()) {
            var message = "贝壳未取回可导入的房源（列表页可能返回空页或验证码，稍后重试或减少页数）。";
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
        importPayload.put("cleanupMissing", cleanupMissing);
        var result = ingestionService.importRows(importPayload);
        result.put("summary", "贝壳上海租房采集完成：抓取 " + items.size() + " 条，" + result.get("summary"));
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

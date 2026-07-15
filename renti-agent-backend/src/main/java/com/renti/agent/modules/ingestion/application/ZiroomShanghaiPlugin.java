package com.renti.agent.modules.ingestion.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 自如「上海租房」插件（id=ziroom_shanghai, provider=ziroom）：调用 scripts/ziroom_ingest.cjs
 * 用 puppeteer-core 无头渲染 sh.ziroom.com 列表页，取回 items 后进程内 {@link IngestionService#importRows}
 * 落候选审核流。
 *
 * <p>自如为长租公寓自持自营，房源均真实在租、无中介钓鱼房，真实性最高、反爬相对可控。价格在列表页用
 * 雪碧图字体加密，脚本按「参考字形模板匹配」在抓取时动态解码（自如每次请求轮换雪碧图）；解码失败的
 * 房源 rent_price=0，因缺发布必填字段而滞留人工审核。抓取失败降级为 {ok:false} 并记录 failed 任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZiroomShanghaiPlugin implements CrawlerPlugin {

    static final String BASE_URL = "https://sh.ziroom.com/z/";
    static final String SCRIPT_PATH = "scripts/ziroom_ingest.cjs";

    private final IngestionService ingestionService;
    private final NodeCrawlerRunner crawlerRunner;
    private final ShanghaiGeocodeEnricher geocodeEnricher;

    private volatile Process currentProcess;
    private volatile boolean stopRequested;

    @Override
    public String id() {
        return "ziroom_shanghai";
    }

    @Override
    public String label() {
        return "自如上海租房";
    }

    @Override
    public String provider() {
        return "ziroom";
    }

    @Override
    public String city() {
        return "上海";
    }

    @Override
    public String description() {
        return "用本机 Chrome 无头渲染 sh.ziroom.com 公开租房列表页，读取自如自营房源（真实性最高）；"
                + "价格雪碧图在抓取时字形模板匹配动态解码，进入候选审核流；不登录、不绕过验证码。";
    }

    @Override
    public Map<String, Object> defaultOptions() {
        return Map.of(
                "pages", 3,
                "limit", 100,
                "cleanupMissing", false);
    }

    @Override
    public List<String> capabilities() {
        return List.of("one_click_run", "scheduled_run", "stoppable", "self_operated", "review_flow", "pagination");
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
            items = crawlerRunner.fetchItems(SCRIPT_PATH, provider(), opts, process -> currentProcess = process);
        } catch (Exception exception) {
            var message = stopRequested
                    ? "自如采集已被手动停止。"
                    : "自如采集脚本执行失败：" + String.valueOf(exception.getMessage());
            log.warn("[{}] {}", id(), message);
            var job = ingestionService.recordFailedJob(
                    id(), provider(), "public_listing_page", BASE_URL, city(), message);
            return failResult(job.getId(), message);
        } finally {
            currentProcess = null;
        }
        if (items.isEmpty()) {
            var message = "自如未取回可导入的房源（列表页可能返回空页或结构变化，稍后重试）。";
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
        result.put("summary", "自如上海租房采集完成：抓取 " + items.size() + " 条，" + result.get("summary"));
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

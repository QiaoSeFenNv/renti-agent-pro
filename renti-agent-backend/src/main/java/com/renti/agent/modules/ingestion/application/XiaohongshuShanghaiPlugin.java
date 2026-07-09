package com.renti.agent.modules.ingestion.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 小红书「上海租房」渠道插件：调用 scripts/xhs_ingest.py（opencli 驱动本机已登录
 * Chrome 抓取搜索结果与笔记正文，DeepSeek 结构化提取），以 items 模式取回数据后
 * 进程内走 {@link IngestionService#importRows}，落入候选审核流（pending）。
 *
 * <p>前置条件：本机安装 @jackwener/opencli、Chrome 已登录小红书、OpenCLI 扩展在线
 * （可用 `opencli doctor` 自检）。抓取失败降级为 {ok:false} 并记录 failed 任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XiaohongshuShanghaiPlugin implements CrawlerPlugin {

    static final String BASE_URL = "https://www.xiaohongshu.com";
    private static final long TIMEOUT_MINUTES = 15;

    private final IngestionService ingestionService;
    private final ObjectMapper objectMapper;

    /** 当前采集子进程（同一时刻至多一个运行；停止 = 杀进程树） */
    private volatile Process currentProcess;
    private volatile boolean stopRequested;

    @Override
    public String id() {
        return "xiaohongshu_shanghai";
    }

    @Override
    public String label() {
        return "小红书上海租房笔记";
    }

    @Override
    public String provider() {
        return "xiaohongshu";
    }

    @Override
    public String city() {
        return "上海";
    }

    @Override
    public String description() {
        return "通过本机 opencli 驱动已登录 Chrome 搜索小红书「上海租房」笔记并抓取正文，"
                + "DeepSeek 结构化提取租金/户型/区域后进入候选审核流；"
                + "需本机 Chrome 已登录小红书且 OpenCLI 扩展在线（opencli doctor 自检）。";
    }

    @Override
    public Map<String, Object> defaultOptions() {
        return Map.of(
                "keyword", "上海租房",
                "limit", 12,
                "skipDetail", false);
    }

    @Override
    public List<String> capabilities() {
        return List.of("one_click_run", "scheduled_run", "stoppable", "llm_extract", "review_flow");
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
        log.info("[xiaohongshu_shanghai] 已请求终止采集子进程 pid={}", process.pid());
        return true;
    }

    @Override
    public Map<String, Object> run(Map<String, Object> options) {
        var opts = options == null ? Map.<String, Object>of() : options;
        var keyword = String.valueOf(opts.getOrDefault("keyword", "上海租房"));
        if (keyword.isBlank()) {
            keyword = "上海租房";
        }
        int limit = intOption(opts.get("limit"), 12);
        boolean skipDetail = Boolean.TRUE.equals(opts.get("skipDetail"));

        stopRequested = false;
        List<Map<String, Object>> items;
        try {
            items = fetchItems(keyword, limit, skipDetail, opts);
        } catch (Exception exception) {
            var message = stopRequested
                    ? "小红书采集已被手动停止。"
                    : "小红书采集脚本执行失败：" + String.valueOf(exception.getMessage());
            log.warn("[xiaohongshu_shanghai] {}", message);
            var job = ingestionService.recordFailedJob(
                    "xiaohongshu_shanghai", provider(), "public_listing_page", BASE_URL, city(), message);
            return failResult(job.getId(), message);
        } finally {
            currentProcess = null;
        }
        if (items.isEmpty()) {
            var message = "小红书搜索「" + keyword + "」未取回可导入的笔记（登录态或浏览器桥接可能失效，"
                    + "请运行 opencli doctor / opencli xiaohongshu whoami 自检）。";
            var job = ingestionService.recordFailedJob(
                    "xiaohongshu_shanghai", provider(), "public_listing_page", BASE_URL, city(), message);
            return failResult(job.getId(), message);
        }

        var importPayload = new LinkedHashMap<String, Object>();
        importPayload.put("items", items);
        importPayload.put("sourceName", "小红书");
        importPayload.put("provider", provider());
        importPayload.put("sourceType", "public_listing_page");
        importPayload.put("jobType", "crawler");
        importPayload.put("city", city());
        importPayload.put("baseUrl", BASE_URL);
        importPayload.put("cleanupMissing", false);
        var result = ingestionService.importRows(importPayload);
        result.put("summary", "小红书「" + keyword + "」采集完成：" + result.get("summary"));
        return result;
    }

    /** 执行 python 脚本（--output items），stdout 返回 {ok, items:[...]} */
    private List<Map<String, Object>> fetchItems(String keyword, int limit, boolean skipDetail,
                                                 Map<String, Object> opts) throws Exception {
        var scriptPath = Path.of(String.valueOf(opts.getOrDefault("scriptPath", "scripts/xhs_ingest.py")))
                .toAbsolutePath();
        if (!Files.isRegularFile(scriptPath)) {
            throw new IOException("采集脚本不存在：" + scriptPath);
        }
        var pythonBin = String.valueOf(opts.getOrDefault("pythonBin", "python"));

        var command = new ArrayList<String>();
        command.add(pythonBin);
        command.add(scriptPath.toString());
        command.add("--output");
        command.add("items");
        command.add("--keyword");
        command.add(keyword);
        command.add("--limit");
        command.add(String.valueOf(Math.max(1, Math.min(limit, 50))));
        if (skipDetail) {
            command.add("--skip-detail");
        }

        log.info("[xiaohongshu_shanghai] 执行采集脚本：{}", String.join(" ", command));
        var builder = new ProcessBuilder(command);
        builder.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();
        currentProcess = process;
        String stdout;
        try (var stream = process.getInputStream()) {
            stdout = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("脚本执行超时（>" + TIMEOUT_MINUTES + " 分钟）");
        }
        if (process.exitValue() != 0) {
            throw new IOException("脚本退出码 " + process.exitValue());
        }

        int start = stdout.indexOf('{');
        if (start < 0) {
            throw new IOException("脚本未输出 JSON：" + stdout.substring(0, Math.min(stdout.length(), 160)));
        }
        Map<String, Object> parsed = objectMapper.readValue(stdout.substring(start), objectMapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, Object.class));
        if (!(parsed.get("items") instanceof List<?> rows)) {
            throw new IOException("脚本输出缺少 items 字段");
        }
        var items = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            if (row instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                var casted = (Map<String, Object>) map;
                items.add(new LinkedHashMap<>(casted));
            }
        }
        return items;
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

    private static int intOption(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

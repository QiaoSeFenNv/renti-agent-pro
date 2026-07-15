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
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Node 抓取脚本运行器：定位本机 node，执行 scripts/beike_ingest.cjs（--output items），
 * 解析 stdout 的 {"ok":..,"items":[...]} 并返回行数组。供贝壳/链家插件复用（同一脚本 --provider 区分）。
 *
 * <p>脚本用 puppeteer-core 驱动本机 Chrome 无头渲染贝壳/链家列表页，出站走代理。抓取失败/超时抛异常，
 * 由调用方降级为 failed 任务。支持通过 stopCurrentRun 杀子进程树。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeCrawlerRunner {

    private static final long TIMEOUT_MINUTES = 12;
    static final String SCRIPT_PATH = "scripts/beike_ingest.cjs";

    private final ObjectMapper objectMapper;

    /** 运行默认抓取脚本（贝壳/链家共用 beike_ingest.cjs）。 */
    public List<Map<String, Object>> fetchItems(String provider, Map<String, Object> options,
                                                ProcessSink sink) throws Exception {
        return fetchItems(SCRIPT_PATH, provider, options, sink);
    }

    /** 运行指定抓取脚本，返回 snake_case 行数组（失败抛异常）。 */
    public List<Map<String, Object>> fetchItems(String scriptRelPath, String provider, Map<String, Object> options,
                                                ProcessSink sink) throws Exception {
        var scriptPath = Path.of(scriptRelPath).toAbsolutePath();
        if (!Files.isRegularFile(scriptPath)) {
            throw new IOException("抓取脚本不存在：" + scriptPath);
        }
        var opts = options == null ? Map.<String, Object>of() : options;

        var command = new ArrayList<String>();
        command.add(resolveNodeBin());
        command.add(scriptPath.toString());
        command.add("--provider");
        command.add(provider);
        command.add("--pages");
        command.add(String.valueOf(intOption(opts.get("pages"), 2, 1, 10)));
        command.add("--limit");
        command.add(String.valueOf(intOption(opts.get("limit"), 120, 1, 500)));
        command.add("--output");
        command.add("items");
        var districts = String.valueOf(opts.getOrDefault("districts", "")).trim();
        if (!districts.isEmpty() && !"null".equalsIgnoreCase(districts)) {
            command.add("--districts");
            command.add(districts);
        }
        // 仅当显式传入合法 http 入口时才覆盖默认列表页；CrawlerPluginRegistry.pluginOptions 会给
        // 无 url 默认值的插件注入字符串 "null"，此处需过滤，否则脚本会导航到 "null/"。
        var url = String.valueOf(opts.getOrDefault("url", "")).trim();
        if (districts.isEmpty() && url.startsWith("http")) {
            command.add("--url");
            command.add(url);
        }

        log.info("[{}] 执行抓取脚本：{}", provider, String.join(" ", command));
        var builder = new ProcessBuilder(command);
        builder.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");
        var errLog = Path.of("scripts", "beike-last-stderr.log").toAbsolutePath();
        builder.redirectError(ProcessBuilder.Redirect.to(errLog.toFile()));
        var process = builder.start();
        if (sink != null) {
            sink.accept(process);
        }
        String stdout;
        try (var stream = process.getInputStream()) {
            stdout = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("抓取脚本超时（>" + TIMEOUT_MINUTES + " 分钟）");
        }
        if (process.exitValue() != 0) {
            throw new IOException("抓取脚本退出码 " + process.exitValue() + "：" + tail(stdout));
        }

        int start = stdout.indexOf('{');
        if (start < 0) {
            throw new IOException("脚本未输出 JSON：" + tail(stdout));
        }
        Map<String, Object> parsed = objectMapper.readValue(stdout.substring(start), objectMapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, Object.class));
        if (Boolean.FALSE.equals(parsed.get("ok")) && parsed.get("error") != null) {
            throw new IOException("脚本报错：" + parsed.get("error"));
        }
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

    /** 接收子进程句柄的回调（供插件登记以支持停止）。 */
    @FunctionalInterface
    public interface ProcessSink {
        void accept(Process process);
    }

    /** 定位 node：环境变量 RENTI_NODE_BIN > fnm 安装目录 > PATH 上的 node。 */
    static String resolveNodeBin() {
        var explicit = System.getenv("RENTI_NODE_BIN");
        if (explicit != null && !explicit.isBlank() && Files.isRegularFile(Path.of(explicit))) {
            return explicit;
        }
        var fnmRoot = Path.of(System.getProperty("user.home"), "AppData", "Roaming", "fnm", "node-versions");
        if (Files.isDirectory(fnmRoot)) {
            try (Stream<Path> versions = Files.list(fnmRoot)) {
                var found = versions
                        .map(version -> version.resolve("installation").resolve("node.exe"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .reduce((a, b) -> b);
                if (found.isPresent()) {
                    return found.get().toString();
                }
            } catch (IOException ignored) {
                // 回退到 PATH
            }
        }
        return "node";
    }

    private static String tail(String value) {
        var text = value == null ? "" : value.trim();
        return text.length() <= 200 ? text : text.substring(text.length() - 200);
    }

    private static int intOption(Object value, int fallback, int min, int max) {
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }
}

package com.renti.agent.modules.ingestion.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 爬虫插件注册表：汇集全部内置插件（Spring 组件自动发现），
 * 提供 ID 归一查找、公开元信息与安全运行（异常降级为 ok:false）。
 */
@Slf4j
@Component
public class CrawlerPluginRegistry {

    private final Map<String, CrawlerPlugin> plugins = new LinkedHashMap<>();

    public CrawlerPluginRegistry(List<CrawlerPlugin> discovered) {
        discovered.stream()
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .forEach(plugin -> plugins.put(plugin.id(), plugin));
        log.info("Registered {} crawler plugins: {}", plugins.size(), plugins.keySet());
    }

    public List<CrawlerPlugin> all() {
        return List.copyOf(plugins.values());
    }

    public Optional<CrawlerPlugin> find(String pluginId) {
        return Optional.ofNullable(plugins.get(normalizeId(pluginId)));
    }

    public static String normalizeId(String value) {
        return String.valueOf(value == null ? "" : value).trim().toLowerCase().replace("-", "_");
    }

    /** 公开插件元信息（旧 _public_plugin） */
    public Map<String, Object> publicPlugin(CrawlerPlugin plugin) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", plugin.id());
        payload.put("label", plugin.label());
        payload.put("provider", plugin.provider());
        payload.put("city", plugin.city());
        payload.put("description", plugin.description());
        payload.put("defaultOptions", new LinkedHashMap<>(plugin.defaultOptions()));
        payload.put("capabilities", List.copyOf(plugin.capabilities()));
        return payload;
    }

    /** 合并默认参数并规整类型（旧 _plugin_options） */
    public Map<String, Object> pluginOptions(CrawlerPlugin plugin, Map<String, Object> payload) {
        var value = new LinkedHashMap<>(plugin.defaultOptions());
        if (payload != null) {
            value.putAll(payload);
        }
        var url = String.valueOf(value.get("url") == null ? "" : value.get("url")).trim();
        value.put("url", url.isEmpty() ? String.valueOf(plugin.defaultOptions().get("url")) : url);
        value.put("pages", boundedPages(value.get("pages"), plugin));
        value.put("geocode", !Boolean.FALSE.equals(value.get("geocode")));
        value.put("cleanupMissing", !Boolean.FALSE.equals(value.get("cleanupMissing")));
        return value;
    }

    /** 安全运行插件：异常降级为 {ok:false, code: crawl_failed} */
    public Map<String, Object> run(CrawlerPlugin plugin, Map<String, Object> options) {
        try {
            return plugin.run(options);
        } catch (Exception exception) {
            log.error("Crawler plugin {} failed", plugin.id(), exception);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("ok", false);
            payload.put("code", "crawl_failed");
            payload.put("summary", "采集插件执行失败：" + exception.getMessage());
            payload.put("fieldErrors", Map.of());
            return payload;
        }
    }

    private static int boundedPages(Object value, CrawlerPlugin plugin) {
        int defaultPages = plugin.defaultOptions().get("pages") instanceof Number number ? number.intValue() : 1;
        int parsed = defaultPages;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 保持默认页数
            }
        }
        return Math.max(1, Math.min(10, parsed));
    }
}

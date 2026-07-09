package com.renti.agent.modules.ingestion.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.infrastructure.persistence.entity.CrawlerScheduleEntity;
import com.renti.agent.infrastructure.persistence.repository.CrawlerScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 爬虫调度：插件列表、调度配置读写、到期任务触发
 * （对齐旧 listing_crawler_plugins.py 的 schedule store 与 payload 函数）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlerScheduleService {

    private final CrawlerScheduleRepository scheduleRepository;
    private final CrawlerPluginRegistry pluginRegistry;

    /** 插件列表（含各自调度状态） */
    @Transactional
    public Map<String, Object> pluginsPayload() {
        var schedules = new LinkedHashMap<String, Map<String, Object>>();
        for (var schedule : listScheduleEntities()) {
            schedules.put(schedule.getPluginId(), schedulePayload(schedule));
        }
        var plugins = new ArrayList<Map<String, Object>>();
        for (var plugin : pluginRegistry.all()) {
            var payload = pluginRegistry.publicPlugin(plugin);
            payload.put("schedule", schedules.get(plugin.id()));
            plugins.add(payload);
        }
        return Map.of("ok", true, "plugins", plugins);
    }

    /** 手动运行插件并落调度运行记录 */
    @Transactional
    public Map<String, Object> runPlugin(String pluginId, Map<String, Object> payload) {
        var plugin = pluginRegistry.find(pluginId).orElse(null);
        if (plugin == null) {
            return IngestionService.error("plugin_not_found", "采集插件不存在。");
        }
        var options = pluginRegistry.pluginOptions(plugin, optionsFrom(payload));
        var result = pluginRegistry.run(plugin, options);
        markRun(plugin.id(), result, OffsetDateTime.now());
        return result;
    }

    /** 停止插件当前运行（仅支持 stoppable 的插件，如小红书渠道的采集子进程） */
    public Map<String, Object> stopPlugin(String pluginId) {
        var plugin = pluginRegistry.find(pluginId).orElse(null);
        if (plugin == null) {
            return IngestionService.error("plugin_not_found", "采集插件不存在。");
        }
        if (!plugin.supportsStop()) {
            return IngestionService.error("stop_unsupported", "该插件不支持停止运行。");
        }
        boolean stopped = plugin.stopCurrentRun();
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("stopped", stopped);
        body.put("summary", stopped ? "已发送停止指令，采集进程正在终止。" : "当前没有正在运行的采集任务。");
        return body;
    }

    /** 直连爬取（不落调度运行记录，对齐旧 /crawl/lianjia-shanghai 端点） */
    @Transactional
    public Map<String, Object> crawlDirect(String pluginId, Map<String, Object> payload) {
        var plugin = pluginRegistry.find(pluginId).orElse(null);
        if (plugin == null) {
            return IngestionService.error("plugin_not_found", "采集插件不存在。");
        }
        var options = pluginRegistry.pluginOptions(plugin, payload == null ? Map.of() : payload);
        return pluginRegistry.run(plugin, options);
    }

    @Transactional
    public Map<String, Object> schedulesPayload() {
        var schedules = listScheduleEntities().stream().map(this::schedulePayload).toList();
        return Map.of("ok", true, "schedules", schedules);
    }

    @Transactional
    public Map<String, Object> updateSchedule(String pluginId, Map<String, Object> payload) {
        var plugin = pluginRegistry.find(pluginId).orElse(null);
        if (plugin == null) {
            return IngestionService.error("plugin_not_found", "采集插件不存在。");
        }
        var body = payload == null ? Map.<String, Object>of() : payload;
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        int intervalMinutes = boundedInterval(body.get("intervalMinutes"));
        var options = pluginRegistry.pluginOptions(plugin, optionsFrom(body));
        var now = OffsetDateTime.now();

        var schedule = scheduleRepository.findById(plugin.id()).orElseGet(() -> {
            var created = new CrawlerScheduleEntity();
            created.setPluginId(plugin.id());
            return created;
        });
        schedule.setEnabled(enabled);
        schedule.setIntervalMinutes(intervalMinutes);
        schedule.setOptions(options);
        schedule.setNextRunAt(enabled ? now.plusMinutes(intervalMinutes) : null);
        schedule.setUpdatedAt(now);
        scheduleRepository.save(schedule);
        log.info("Updated crawler schedule {} enabled={} interval={}m", plugin.id(), enabled, intervalMinutes);

        return Map.of(
                "ok", true,
                "schedule", schedulePayload(schedule),
                "summary", enabled ? "定时采集已启用。" : "定时采集已关闭。");
    }

    /** 运行全部到期调度（每次最多 3 个，对齐旧 run_due） */
    @Transactional
    public Map<String, Object> runDue(OffsetDateTime now) {
        var due = scheduleRepository.findDue(now, PageRequest.of(0, 3));
        var results = new ArrayList<Map<String, Object>>();
        for (var schedule : due) {
            var pluginId = schedule.getPluginId();
            var plugin = pluginRegistry.find(pluginId).orElse(null);
            Map<String, Object> result;
            if (plugin == null) {
                result = IngestionService.error("plugin_not_found", "采集插件不存在。");
            } else {
                var options = schedule.getOptions() == null || schedule.getOptions().isEmpty()
                        ? new LinkedHashMap<String, Object>(plugin.defaultOptions())
                        : new LinkedHashMap<>(schedule.getOptions());
                result = pluginRegistry.run(plugin, options);
            }
            markRun(pluginId, result, now);
            results.add(Map.of("pluginId", pluginId, "result", result));
        }
        return Map.of("ok", true, "ran", results.size(), "results", results);
    }

    /** 记录运行结果并推进 nextRunAt（对齐旧 mark_run） */
    @Transactional
    public void markRun(String pluginId, Map<String, Object> result, OffsetDateTime now) {
        var schedule = scheduleRepository.findById(CrawlerPluginRegistry.normalizeId(pluginId)).orElse(null);
        if (schedule == null) {
            return;
        }
        var summary = String.valueOf(result.getOrDefault("summary", ""));
        schedule.setLastRunAt(now);
        schedule.setLastStatus(Boolean.TRUE.equals(result.get("ok")) ? "success" : "error");
        schedule.setLastSummary(summary.length() > 500 ? summary.substring(0, 500) : summary);
        schedule.setLastJobId(result.get("jobId") instanceof Number number ? number.longValue() : null);
        schedule.setNextRunAt(schedule.isEnabled() ? now.plusMinutes(schedule.getIntervalMinutes()) : null);
        schedule.setUpdatedAt(OffsetDateTime.now());
        scheduleRepository.save(schedule);
    }

    // ---------- 内部 ----------

    private List<CrawlerScheduleEntity> listScheduleEntities() {
        ensureDefaultRows();
        return scheduleRepository.findAllByOrderByPluginIdAsc();
    }

    private void ensureDefaultRows() {
        for (var plugin : pluginRegistry.all()) {
            if (scheduleRepository.existsById(plugin.id())) {
                continue;
            }
            var schedule = new CrawlerScheduleEntity();
            schedule.setPluginId(plugin.id());
            schedule.setEnabled(false);
            schedule.setIntervalMinutes(1440);
            schedule.setOptions(new LinkedHashMap<>(plugin.defaultOptions()));
            scheduleRepository.save(schedule);
        }
    }

    private Map<String, Object> schedulePayload(CrawlerScheduleEntity schedule) {
        var options = new LinkedHashMap<String, Object>();
        var plugin = pluginRegistry.find(schedule.getPluginId()).orElse(null);
        if (plugin != null) {
            options.putAll(plugin.defaultOptions());
        }
        if (schedule.getOptions() != null) {
            options.putAll(schedule.getOptions());
        }
        if (plugin instanceof AnjukeShanghaiPlugin) {
            options.put("url", AnjukeShanghaiPlugin.normalizeBaseUrl(String.valueOf(options.get("url"))));
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("pluginId", schedule.getPluginId());
        payload.put("enabled", schedule.isEnabled());
        payload.put("intervalMinutes", schedule.getIntervalMinutes());
        payload.put("options", options);
        payload.put("nextRunAt", dateTimeText(schedule.getNextRunAt()));
        payload.put("lastRunAt", dateTimeText(schedule.getLastRunAt()));
        payload.put("lastStatus", schedule.getLastStatus() == null ? "" : schedule.getLastStatus());
        payload.put("lastSummary", schedule.getLastSummary() == null ? "" : schedule.getLastSummary());
        payload.put("lastJobId", schedule.getLastJobId());
        payload.put("updatedAt", dateTimeText(schedule.getUpdatedAt()));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionsFrom(Map<String, Object> payload) {
        if (payload == null) {
            return Map.of();
        }
        if (payload.get("options") instanceof Map<?, ?> options) {
            return (Map<String, Object>) options;
        }
        return payload;
    }

    private static int boundedInterval(Object value) {
        int parsed = 1440;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 保持默认间隔
            }
        }
        return Math.max(15, Math.min(10_080, parsed));
    }

    private static String dateTimeText(OffsetDateTime value) {
        return value == null ? "" : value.toString();
    }
}

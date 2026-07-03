package com.renti.agent.modules.ingestion.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.modules.ingestion.domain.ListingNormalizer;
import lombok.extern.slf4j.Slf4j;

/**
 * 上海公开列表爬虫插件基类：分页抓取 + 高德坐标补全 + 导入候选，
 * 抓取为空/网络失败时降级为 {ok:false} 并记录 failed 任务。
 */
@Slf4j
abstract class AbstractShanghaiCrawlerPlugin implements CrawlerPlugin {

    protected final IngestionService ingestionService;
    protected final AmapGeocoder amapGeocoder;

    protected AbstractShanghaiCrawlerPlugin(IngestionService ingestionService, AmapGeocoder amapGeocoder) {
        this.ingestionService = ingestionService;
        this.amapGeocoder = amapGeocoder;
    }

    @Override
    public String city() {
        return "上海";
    }

    /** 单页抓取 + 解析（子类实现站点差异） */
    protected abstract List<Map<String, Object>> fetchPage(String baseUrl, int page, PageContext context)
            throws Exception;

    protected abstract String sourceName();

    protected abstract String emptySummary();

    protected abstract String doneSummaryPrefix();

    /** 页间共享状态（Referer/Cookie 会话等） */
    protected static final class PageContext {
        String referer = "";
    }

    protected Map<String, Object> crawlAndImport(Map<String, Object> options, boolean defaultCleanupMissing) {
        var baseUrl = ListingNormalizer.cleanText(
                ListingNormalizer.first(options, "url", "baseUrl"), 500);
        if (baseUrl.isEmpty()) {
            baseUrl = String.valueOf(defaultOptions().get("url"));
        }
        int pages = bounded(options.get("pages"), 1, 1, 10);
        boolean geocode = !Boolean.FALSE.equals(options.get("geocode"));
        boolean cleanupMissing = options.containsKey("cleanupMissing")
                ? Boolean.TRUE.equals(options.get("cleanupMissing"))
                : defaultCleanupMissing;

        var allItems = new ArrayList<Map<String, Object>>();
        var fetchErrors = new ArrayList<String>();
        var context = new PageContext();
        for (int page = 1; page <= pages; page++) {
            List<Map<String, Object>> pageItems;
            try {
                pageItems = fetchPage(baseUrl, page, context);
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                fetchErrors.add("第 " + page + " 页抓取失败：" + exception.getMessage());
                continue;
            }
            if (geocode) {
                pageItems.forEach(this::enrichWithGeocode);
            }
            allItems.addAll(pageItems);
        }

        if (allItems.isEmpty()) {
            var joinedErrors = String.join("；", fetchErrors);
            var failedJob = ingestionService.recordFailedJob(
                    sourceName(), provider(), "public_listing_page", baseUrl, city(),
                    joinedErrors.isEmpty() ? emptySummary() : joinedErrors);
            log.warn("Crawler plugin {} fetched no items, recorded failed job {}", id(), failedJob.getId());
            var payload = new LinkedHashMap<String, Object>();
            payload.put("ok", false);
            payload.put("code", "crawl_empty");
            payload.put("summary", emptySummary());
            payload.put("fieldErrors", Map.of("fetch", joinedErrors));
            payload.put("itemsFetched", 0);
            payload.put("jobId", failedJob.getId());
            return payload;
        }

        var importPayload = new LinkedHashMap<String, Object>();
        importPayload.put("sourceName", sourceName());
        importPayload.put("provider", provider());
        importPayload.put("sourceType", "public_listing_page");
        importPayload.put("city", city());
        importPayload.put("baseUrl", baseUrl);
        importPayload.put("jobType", "crawler");
        importPayload.put("cleanupMissing", cleanupMissing);
        importPayload.put("items", allItems);
        var result = ingestionService.importRows(importPayload);

        var payload = new LinkedHashMap<String, Object>(result);
        payload.put("itemsFetched", allItems.size());
        payload.put("pagesFetched", pages);
        payload.put("fetchErrors", fetchErrors);
        payload.put("summary", doneSummaryPrefix() + "：抓取 " + allItems.size() + " 条，"
                + String.valueOf(result.getOrDefault("summary", "")));
        return payload;
    }

    /** 坐标补全（旧 _enrich_with_geocode）：地址逐级降级尝试，全部失败记录 geocode_error */
    private void enrichWithGeocode(Map<String, Object> item) {
        if (!ListingNormalizer.isEmpty(item.get("longitude")) && !ListingNormalizer.isEmpty(item.get("latitude"))) {
            return;
        }
        if (!amapGeocoder.available()) {
            return;
        }
        var addresses = geocodeAddresses(item);
        if (addresses.isEmpty()) {
            return;
        }
        var errors = new ArrayList<String>();
        for (var address : addresses) {
            try {
                var result = amapGeocoder.geocode(address, city());
                if (result.isPresent()) {
                    var geocode = result.get();
                    item.put("longitude", geocode.longitude());
                    item.put("latitude", geocode.latitude());
                    item.put("geocode_label", geocode.label());
                    item.put("geocode_query", address);
                    if (!geocode.district().isEmpty()
                            && ListingNormalizer.cleanText(item.get("district"), 40).isEmpty()) {
                        item.put("district", geocode.district());
                    }
                    return;
                }
            } catch (Exception exception) {
                errors.add(String.valueOf(exception.getMessage()));
            }
        }
        var joined = String.join("；", errors.stream().filter(error -> !error.isEmpty()).toList());
        item.put("geocode_error", joined.length() > 240 ? joined.substring(0, 240) : joined);
    }

    private List<String> geocodeAddresses(Map<String, Object> item) {
        var district = ListingNormalizer.cleanText(item.get("district"), 40);
        var businessArea = ListingNormalizer.cleanText(item.get("business_area"), 80);
        var community = ListingNormalizer.cleanText(item.get("community"), 120);
        if (community.isEmpty()) {
            return List.of();
        }
        var candidates = List.of(
                "上海市" + district + businessArea + community,
                "上海市" + district + community,
                "上海市" + businessArea + community,
                "上海市" + community);
        var result = new ArrayList<String>();
        for (var address : candidates) {
            var cleaned = address.replaceAll("\\s+", "");
            if (cleaned.length() > 4 && !result.contains(cleaned)) {
                result.add(cleaned);
            }
        }
        return result;
    }

    protected static int bounded(Object value, int defaultValue, int minimum, int maximum) {
        int parsed = defaultValue;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 保持默认值，对齐旧 _bounded_int
            }
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }
}

package com.renti.agent.modules.ingestion.api;

import java.util.Map;

import com.renti.agent.modules.ingestion.application.CrawlerScheduleService;
import com.renti.agent.modules.ingestion.application.IngestionService;
import com.renti.agent.modules.ingestion.application.ListingVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集管理端点（需管理员登录）：概览、候选审核、导入、爬虫插件与调度。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/listing-ingestion")
@RequiredArgsConstructor
public class AdminIngestionController {

    private final IngestionService ingestionService;
    private final CrawlerScheduleService crawlerScheduleService;
    private final ListingVerificationService listingVerificationService;

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return ingestionService.overview();
    }

    @GetMapping("/candidates")
    public Map<String, Object> candidates(@RequestParam(defaultValue = "pending") String status,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) Integer page) {
        return ingestionService.candidatesPayload(status, limit, page);
    }

    @PostMapping("/candidates/{candidateId}/approve")
    public Map<String, Object> approve(@PathVariable long candidateId) {
        return ingestionService.approve(candidateId);
    }

    @PostMapping("/candidates/{candidateId}/reject")
    public Map<String, Object> reject(@PathVariable long candidateId,
                                      @RequestBody(required = false) Map<String, Object> payload) {
        var note = payload == null || payload.get("note") == null ? "" : String.valueOf(payload.get("note"));
        return ingestionService.reject(candidateId, note);
    }

    @PostMapping("/candidates/bulk-approve")
    public Map<String, Object> bulkApprove(@RequestBody(required = false) Map<String, Object> payload) {
        return ingestionService.bulkApprove(payload == null ? Map.of() : payload);
    }

    @PostMapping("/candidates/bulk-reject")
    public Map<String, Object> bulkReject(@RequestBody(required = false) Map<String, Object> payload) {
        return ingestionService.bulkReject(payload == null ? Map.of() : payload);
    }

    @PostMapping("/import")
    public Map<String, Object> importListings(@RequestBody(required = false) Map<String, Object> payload) {
        return ingestionService.importRows(payload == null ? Map.of() : payload);
    }

    /** 手动触发一批房源官方核验（异步核验器同款逻辑）。 */
    @PostMapping("/verify-listings")
    public Map<String, Object> verifyListings(@RequestBody(required = false) Map<String, Object> payload) {
        int limit = 40;
        if (payload != null && payload.get("limit") != null) {
            try {
                limit = Integer.parseInt(String.valueOf(payload.get("limit")).trim());
            } catch (NumberFormatException ignored) {
                limit = 40;
            }
        }
        return listingVerificationService.verifyBatch(limit);
    }

    @GetMapping("/crawler-plugins")
    public Map<String, Object> crawlerPlugins() {
        return crawlerScheduleService.pluginsPayload();
    }

    @PostMapping("/crawler-plugins/{pluginId}/run")
    public Map<String, Object> runPlugin(@PathVariable String pluginId,
                                         @RequestBody(required = false) Map<String, Object> payload) {
        return crawlerScheduleService.runPlugin(pluginId, payload);
    }

    @PostMapping("/crawler-plugins/{pluginId}/stop")
    public Map<String, Object> stopPlugin(@PathVariable String pluginId) {
        return crawlerScheduleService.stopPlugin(pluginId);
    }

    @GetMapping("/crawler-schedules")
    public Map<String, Object> schedules() {
        return crawlerScheduleService.schedulesPayload();
    }

    @PutMapping("/crawler-schedules/{pluginId}")
    public Map<String, Object> updateSchedule(@PathVariable String pluginId,
                                              @RequestBody(required = false) Map<String, Object> payload) {
        return crawlerScheduleService.updateSchedule(pluginId, payload);
    }

    @PostMapping("/crawler-schedules/run-due")
    public Map<String, Object> runDue() {
        return crawlerScheduleService.runDue(java.time.OffsetDateTime.now());
    }

    /** 链家上海直连爬取（不记录调度运行状态，对齐旧 /crawl/lianjia-shanghai） */
    @PostMapping("/crawl/lianjia-shanghai")
    public Map<String, Object> crawlLianjiaShanghai(@RequestBody(required = false) Map<String, Object> payload) {
        return crawlerScheduleService.crawlDirect("lianjia_shanghai", payload);
    }
}

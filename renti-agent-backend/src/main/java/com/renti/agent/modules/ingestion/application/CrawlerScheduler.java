package com.renti.agent.modules.ingestion.application;

import java.time.OffsetDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 爬虫定时器：每分钟检查到期调度并触发（对齐旧 start_listing_crawler_scheduler，
 * 环境变量 RENTAI_CRAWLER_SCHEDULER=0/false 可关闭）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlerScheduler {

    private final CrawlerScheduleService scheduleService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void runDueSchedules() {
        var flag = System.getenv("RENTAI_CRAWLER_SCHEDULER");
        if ("0".equals(flag) || "false".equalsIgnoreCase(flag)) {
            return;
        }
        try {
            scheduleService.runDue(OffsetDateTime.now());
        } catch (Exception exception) {
            log.error("Crawler scheduler tick failed", exception);
        }
    }
}

package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 爬虫插件调度配置：每插件一行，控制定时采集的启停、间隔与运行参数。
 */
@Entity
@Table(name = "listing_crawler_schedules", indexes = {
        @Index(name = "idx_crawler_schedules_due", columnList = "enabled,next_run_at"),
})
@Getter
@Setter
@NoArgsConstructor
public class CrawlerScheduleEntity {

    @Id
    @Column(name = "plugin_id", length = 120)
    private String pluginId;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "interval_minutes", nullable = false)
    private int intervalMinutes = 1440;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> options = new LinkedHashMap<>();

    @Column(name = "next_run_at")
    private OffsetDateTime nextRunAt;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "last_status", length = 32)
    private String lastStatus = "";

    @Column(name = "last_summary", columnDefinition = "text")
    private String lastSummary = "";

    @Column(name = "last_job_id")
    private Long lastJobId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}

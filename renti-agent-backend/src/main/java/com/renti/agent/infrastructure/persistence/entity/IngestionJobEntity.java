package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 采集任务：一次导入/爬取批次的执行记录（来源信息直接冗余在任务上）。
 */
@Entity
@Table(name = "listing_ingestion_jobs", indexes = {
        @Index(name = "idx_ingestion_jobs_created", columnList = "created_at"),
        @Index(name = "idx_ingestion_jobs_source", columnList = "source_name,city"),
})
@Getter
@Setter
@NoArgsConstructor
public class IngestionJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_name", nullable = false, length = 120)
    private String sourceName = "manual_import";

    @Column(nullable = false, length = 120)
    private String provider = "manual";

    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType = "manual_upload";

    @Column(name = "base_url", columnDefinition = "text")
    private String baseUrl = "";

    /** manual_import | crawler 等 */
    @Column(name = "job_type", nullable = false, length = 64)
    private String jobType = "manual_import";

    @Column(nullable = false, length = 40)
    private String city = "上海";

    /** pending | running | completed | failed */
    @Column(nullable = false, length = 32)
    private String status = "pending";

    @Column(name = "total_input", nullable = false)
    private int totalInput = 0;

    @Column(name = "candidates_created", nullable = false)
    private int candidatesCreated = 0;

    @Column(name = "candidates_updated", nullable = false)
    private int candidatesUpdated = 0;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage = "";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
}

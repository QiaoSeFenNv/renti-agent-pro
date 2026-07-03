package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 采集候选房源：进入审核流水线的规范化数据，approve 后发布到 listings 表。
 *
 * <p>状态机：pending → approved / rejected。payload 为规范化后的房源 JSON（camelCase 字段），
 * dedupeKey 保证同一来源房源只保留一条候选。</p>
 */
@Entity
@Table(name = "listing_candidates", indexes = {
        @Index(name = "idx_listing_candidates_dedupe", columnList = "dedupe_key", unique = true),
        @Index(name = "idx_listing_candidates_status", columnList = "status,updated_at"),
        @Index(name = "idx_listing_candidates_listing", columnList = "listing_id"),
})
@Getter
@Setter
@NoArgsConstructor
public class ListingCandidateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id")
    private Long jobId;

    /** pending | approved | rejected */
    @Column(nullable = false, length = 32)
    private String status = "pending";

    /** 规范化后的目标房源业务 ID（发布时作为 listings 主键） */
    @Column(name = "listing_id", length = 96)
    private String listingId;

    @Column(name = "dedupe_key", nullable = false, length = 128, unique = true)
    private String dedupeKey;

    /** 规范化房源 payload（camelCase 字段，含 raw 原始数据） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    /** 审核备注（驳回原因等） */
    @Column(columnDefinition = "text")
    private String reason = "";

    @Column(length = 40)
    private String city;

    @Column(length = 80)
    private String provider;

    @Column(name = "external_id", length = 180)
    private String externalId;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;
}

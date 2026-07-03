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
 * 用户搜索历史（对照旧表 renti_user_search_history，每用户保留最近 30 条）。
 */
@Entity
@Table(name = "search_history", indexes = @Index(
        name = "idx_search_history_user", columnList = "user_id,created_at"))
@Getter
@Setter
@NoArgsConstructor
public class SearchHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "query_text", nullable = false, columnDefinition = "text")
    private String queryText = "";

    @Column(nullable = false, length = 32)
    private String source = "text";

    @Column(name = "center_label", nullable = false, length = 180)
    private String centerLabel = "";

    private Double longitude;

    private Double latitude;

    @Column(name = "radius_meters", nullable = false)
    private int radiusMeters = 300;

    @Column(name = "result_count", nullable = false)
    private int resultCount = 0;

    @Column(name = "model_profile", nullable = false, length = 64)
    private String modelProfile = "balanced";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> requestPayload = new LinkedHashMap<>();

    @Column(name = "result_summary", nullable = false, columnDefinition = "text")
    private String summary = "";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}

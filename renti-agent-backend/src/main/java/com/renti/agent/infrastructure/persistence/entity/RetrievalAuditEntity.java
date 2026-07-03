package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 检索审计记录（对照旧表 renti_retrieval_audits，含命中房源与 toolTrace）。
 */
@Entity
@Table(name = "retrieval_audits", indexes = {
        @Index(name = "idx_retrieval_audits_created", columnList = "created_at"),
        @Index(name = "idx_retrieval_audits_user", columnList = "user_id,created_at"),
        @Index(name = "idx_retrieval_audits_endpoint", columnList = "endpoint,created_at"),
})
@Getter
@Setter
@NoArgsConstructor
public class RetrievalAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 160)
    private String endpoint = "";

    @Column(name = "query_text", nullable = false, columnDefinition = "text")
    private String queryText = "";

    @Column(nullable = false, length = 40)
    private String city = "上海";

    @Column(name = "duration_ms", nullable = false)
    private int durationMs = 0;

    @Column(name = "total_hits", nullable = false)
    private int totalHits = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private Map<String, Object> requestPayload = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hits", columnDefinition = "jsonb")
    private List<Object> hits = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_trace", columnDefinition = "jsonb")
    private List<Object> toolTrace = new ArrayList<>();

    @Column(name = "response_summary", nullable = false, columnDefinition = "text")
    private String responseSummary = "";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}

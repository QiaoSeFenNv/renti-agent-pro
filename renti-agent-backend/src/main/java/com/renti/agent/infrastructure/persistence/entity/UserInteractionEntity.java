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
 * 用户交互记录（对照旧表 renti_user_interactions）。
 */
@Entity
@Table(name = "user_interactions", indexes = {
        @Index(name = "idx_user_interactions_created", columnList = "created_at"),
        @Index(name = "idx_user_interactions_user", columnList = "user_id,created_at"),
        @Index(name = "idx_user_interactions_endpoint", columnList = "endpoint,created_at"),
})
@Getter
@Setter
@NoArgsConstructor
public class UserInteractionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 120)
    private String endpoint = "";

    @Column(name = "request_text", nullable = false, columnDefinition = "text")
    private String requestText = "";

    @Column(nullable = false, length = 40)
    private String city = "上海";

    @Column(nullable = false, length = 40)
    private String status = "";

    @Column(name = "duration_ms", nullable = false)
    private int durationMs = 0;

    @Column(name = "result_count", nullable = false)
    private int resultCount = 0;

    @Column(nullable = false, columnDefinition = "text")
    private String summary = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "intent", columnDefinition = "jsonb")
    private Map<String, Object> intent = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_trace", columnDefinition = "jsonb")
    private List<Object> toolTrace = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private Map<String, Object> requestPayload = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private Map<String, Object> responsePayload = new LinkedHashMap<>();

    @Column(name = "error_message", nullable = false, columnDefinition = "text")
    private String errorMessage = "";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}

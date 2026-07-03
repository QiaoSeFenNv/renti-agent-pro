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
 * Agent 执行 trace（对照旧表 renti_agent_traces）。
 */
@Entity
@Table(name = "agent_traces", indexes = {
        @Index(name = "idx_agent_traces_created", columnList = "created_at"),
        @Index(name = "idx_agent_traces_user", columnList = "user_id,created_at"),
        @Index(name = "idx_agent_traces_status", columnList = "status,agent_mode,created_at"),
})
@Getter
@Setter
@NoArgsConstructor
public class AgentTraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "request_text", nullable = false, columnDefinition = "text")
    private String requestText = "";

    @Column(nullable = false, length = 40)
    private String city = "上海";

    @Column(name = "workspace_mode", nullable = false, length = 40)
    private String workspaceMode = "system_search";

    @Column(name = "agent_mode", nullable = false, length = 40)
    private String agentMode = "";

    @Column(nullable = false, length = 40)
    private String status = "";

    @Column(nullable = false, length = 80)
    private String provider = "";

    @Column(nullable = false, length = 120)
    private String model = "";

    @Column(name = "duration_ms", nullable = false)
    private int durationMs = 0;

    @Column(name = "result_count", nullable = false)
    private int resultCount = 0;

    @Column(name = "fallback_reason", nullable = false, columnDefinition = "text")
    private String fallbackReason = "";

    @Column(nullable = false, columnDefinition = "text")
    private String summary = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "intent", columnDefinition = "jsonb")
    private Map<String, Object> intent = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_trace", columnDefinition = "jsonb")
    private List<Object> toolTrace = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "usage_json", columnDefinition = "jsonb")
    private Map<String, Object> usage = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private Map<String, Object> requestPayload = new LinkedHashMap<>();

    @Column(name = "error_message", nullable = false, columnDefinition = "text")
    private String errorMessage = "";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}

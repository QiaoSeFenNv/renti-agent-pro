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
 * 系统日志（api 请求日志 / app 应用日志 / llm 调用日志）。
 */
@Entity
@Table(name = "system_logs", indexes = {
        @Index(name = "idx_system_logs_kind_time", columnList = "kind,created_at"),
})
@Getter
@Setter
@NoArgsConstructor
public class SystemLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** api | app | llm */
    @Column(nullable = false, length = 16)
    private String kind;

    /** info | warn | error */
    @Column(nullable = false, length = 8)
    private String level = "info";

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}

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
 * 邮箱订阅者（double opt-in）。status: pending | confirmed | unsubscribed。
 */
@Entity
@Table(name = "subscribers", indexes = {
        @Index(name = "idx_subscribers_email", columnList = "email", unique = true),
        @Index(name = "idx_subscribers_status", columnList = "status"),
})
@Getter
@Setter
@NoArgsConstructor
public class SubscriberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 32)
    private String status = "pending";

    @Column(name = "source_first", nullable = false, length = 64)
    private String sourceFirst = "home";

    @Column(name = "source_last", nullable = false, length = 64)
    private String sourceLast = "home";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_counts", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> sourceCounts = new LinkedHashMap<>();

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "unsubscribed_at")
    private OffsetDateTime unsubscribedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}

package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 订阅事件流水（request/confirm/unsubscribe），供订阅统计 bySource 使用。
 */
@Entity
@Table(name = "subscription_events")
@Getter
@Setter
@NoArgsConstructor
public class SubscriptionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscriber_id")
    private Long subscriberId;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}

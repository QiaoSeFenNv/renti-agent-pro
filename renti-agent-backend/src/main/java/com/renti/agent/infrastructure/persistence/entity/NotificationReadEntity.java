package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 用户公告已读记录。
 */
@Entity
@Table(name = "notification_reads", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_reads_user_notification", columnNames = {"user_id", "notification_id"}))
@Getter
@Setter
@NoArgsConstructor
public class NotificationReadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "read_at", nullable = false)
    private OffsetDateTime readAt = OffsetDateTime.now();
}

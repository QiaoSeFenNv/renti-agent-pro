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
 * 用户登录会话。数据库仅存 token 的 SHA-256 摘要，原始 token 只出现在 Cookie 中。
 */
@Entity
@Table(name = "user_sessions", indexes = {
        @Index(name = "idx_user_sessions_token", columnList = "token_hash", unique = true),
        @Index(name = "idx_user_sessions_user", columnList = "user_id"),
})
@Getter
@Setter
@NoArgsConstructor
public class UserSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "client_id", length = 64)
    private String clientId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}

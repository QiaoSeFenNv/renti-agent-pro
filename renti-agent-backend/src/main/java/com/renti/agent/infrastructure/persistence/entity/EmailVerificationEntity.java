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
 * 邮箱验证码记录。数据库存 tokenHash（sha256("email-verification:{email}:{code}")），
 * code 明文仅用于开发排查（旧系统模拟发信，验证码通过 email_outbox 表投递）。
 */
@Entity
@Table(name = "email_verifications", indexes = {
        @Index(name = "idx_email_verifications_token", columnList = "token_hash"),
        @Index(name = "idx_email_verifications_email", columnList = "email"),
})
@Getter
@Setter
@NoArgsConstructor
public class EmailVerificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 8)
    private String code;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}

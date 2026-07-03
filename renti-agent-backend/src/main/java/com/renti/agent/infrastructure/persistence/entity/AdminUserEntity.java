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
 * 后台管理员账号（与前台用户完全隔离）。
 */
@Entity
@Table(name = "admin_users", indexes = @Index(name = "idx_admin_users_username", columnList = "username", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class AdminUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "password_salt", length = 128)
    private String passwordSalt;

    @Column(name = "password_iterations")
    private Integer passwordIterations;

    @Column(name = "password_algo", nullable = false, length = 16)
    private String passwordAlgo = "bcrypt";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;
}

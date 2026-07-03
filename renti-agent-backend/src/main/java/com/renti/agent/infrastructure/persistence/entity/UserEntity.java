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
 * 平台注册用户。
 *
 * <p>密码支持两种算法：新用户 bcrypt（passwordAlgo=bcrypt，salt/iterations 为空）；
 * 从旧系统迁移的用户为 pbkdf2（保留 salt 与迭代次数以便兼容校验，
 * 首次登录成功后由服务层升级为 bcrypt）。</p>
 */
@Entity
@Table(name = "users", indexes = @Index(name = "idx_users_email", columnList = "email", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 64)
    private String nickname;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "password_salt", length = 128)
    private String passwordSalt;

    @Column(name = "password_iterations")
    private Integer passwordIterations;

    /** bcrypt | pbkdf2 */
    @Column(name = "password_algo", nullable = false, length = 16)
    private String passwordAlgo = "bcrypt";

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    // 用户找房偏好（DELETE /api/user/preferences 时清空）
    @Column(name = "budget_min")
    private Integer budgetMin;

    @Column(name = "budget_max")
    private Integer budgetMax;

    @Column(name = "commute_target", length = 128)
    private String commuteTarget;

    @Column(name = "commute_minutes")
    private Integer commuteMinutes;

    @Column(name = "favorite_areas", columnDefinition = "text")
    private String favoriteAreas;

    @Column(nullable = false, length = 16)
    private String status = "active";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;
}

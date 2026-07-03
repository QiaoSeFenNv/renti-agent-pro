package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 用户租房偏好（旧系统存于 renti_users 表内的 budget/commute/favorite_areas 字段，
 * 新系统 users 表不含这些列，拆分为独立表，语义与旧版一致）。
 */
@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
public class UserPreferenceEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "budget_min")
    private Integer budgetMin;

    @Column(name = "budget_max")
    private Integer budgetMax;

    @Column(name = "commute_target", length = 160)
    private String commuteTarget;

    @Column(name = "commute_minutes")
    private Integer commuteMinutes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "favorite_areas", columnDefinition = "jsonb")
    private List<String> favoriteAreas = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}

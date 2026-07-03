package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 用户收藏房源（listingId + 收藏时的房源快照）。
 */
@Entity
@Table(name = "user_favorites", uniqueConstraints = @UniqueConstraint(
        name = "uk_user_favorites_user_listing", columnNames = {"user_id", "listing_id"}))
@Getter
@Setter
@NoArgsConstructor
public class UserFavoriteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "listing_id", nullable = false, length = 80)
    private String listingId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "listing_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> listingSnapshot = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}

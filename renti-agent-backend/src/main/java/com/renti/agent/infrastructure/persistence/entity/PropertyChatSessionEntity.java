package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * 房源问答会话（对齐旧 renti_property_chat_sessions 表结构）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "renti_property_chat_sessions", indexes = {
        @Index(name = "idx_property_chat_sessions_user_listing", columnList = "user_id, listing_id")
})
public class PropertyChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "listing_id", nullable = false, length = 120)
    private String listingId;

    @Column(nullable = false, length = 160)
    private String title = "新的房源问答";

    @Column(name = "model_profile", nullable = false, length = 80)
    private String modelProfile = "balanced";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "property_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> propertySnapshot = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}

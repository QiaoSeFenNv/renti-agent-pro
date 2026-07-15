package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 已发布房源（审核通过后的正式库），地图工作台与推荐检索的主数据。
 */
@Entity
@Table(name = "listings", indexes = {
        @Index(name = "idx_listings_city_status", columnList = "city,status"),
        @Index(name = "idx_listings_external", columnList = "provider,external_id"),
})
@Getter
@Setter
@NoArgsConstructor
public class ListingEntity {

    /** 业务主键，如 sh-000123 或采集生成的稳定 ID */
    @Id
    @Column(name = "listing_id", length = 64)
    private String listingId;

    @Column(nullable = false, length = 32)
    private String city;

    @Column(length = 64)
    private String district;

    @Column(name = "business_area", length = 64)
    private String businessArea;

    @Column(length = 128)
    private String community;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false)
    private double longitude;

    @Column(nullable = false)
    private double latitude;

    @Column(name = "rent_price", nullable = false)
    private int rentPrice;

    @Column(length = 32)
    private String layout;

    @Column(name = "area_sqm")
    private Integer areaSqm;

    @Column(name = "rent_type", length = 16)
    private String rentType;

    @Column(name = "nearest_metro", length = 64)
    private String nearestMetro;

    @Column(name = "metro_distance_m")
    private Integer metroDistanceM;

    @Column(name = "commute_minutes")
    private Integer commuteMinutes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_tags", columnDefinition = "jsonb")
    private List<String> riskTags = new ArrayList<>();

    @Column(length = 64)
    private String source;

    @Column(name = "source_name", length = 128)
    private String sourceName;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(length = 64)
    private String provider;

    @Column(name = "external_id", length = 128)
    private String externalId;

    @Column(columnDefinition = "text")
    private String image;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> images = new ArrayList<>();

    /** 采集原始数据，保持可溯源 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> raw = new LinkedHashMap<>();

    /** active | unavailable */
    @Column(nullable = false, length = 16)
    private String status = "active";

    /**
     * 官方核验状态：official_confirmed（调官方接口确认）| platform_certified（来源平台自标官方核验）
     * | official_failed（官方查得未通过/信息不符）| unverified（无核验信号）| null（未跑核验器）。
     */
    @Column(name = "verified", length = 24)
    private String verified;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "unavailable_reason", columnDefinition = "text")
    private String unavailableReason;

    @Column(name = "candidate_id")
    private Long candidateId;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}

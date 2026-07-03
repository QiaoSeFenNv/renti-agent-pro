package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * 房源深度分析缓存：对齐旧 property_analysis.py 把 detail_analysis 挂在房源记录上的行为，
 * 迁移版落独立表（每套房源保留最近一个目标点的分析结果，targetKey 变化即失效重算）。
 */
@Entity
@Table(name = "property_analysis_cache")
@Getter
@Setter
@NoArgsConstructor
public class PropertyAnalysisCacheEntity {

    @Id
    @Column(name = "listing_id", length = 64)
    private String listingId;

    /** 目标点缓存键（"lng,lat" 保留 5 位小数，或 no-target） */
    @Column(name = "target_key", nullable = false, length = 64)
    private String targetKey = "no-target";

    /** 完整分析结果（analysis + insight + analysisMeta） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> analysis = new LinkedHashMap<>();

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt = OffsetDateTime.now();
}

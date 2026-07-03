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
 * 平台配置（KV + JSON 文档）。已知 key：
 * workspace（工作台配置）、system_integrations（外部集成配置中心）。
 */
@Entity
@Table(name = "platform_config")
@Getter
@Setter
@NoArgsConstructor
public class PlatformConfigEntity {

    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configJson = new LinkedHashMap<>();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}

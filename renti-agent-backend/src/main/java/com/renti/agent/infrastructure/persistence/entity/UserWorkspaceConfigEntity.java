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
 * 用户专属工作台配置覆盖（对照旧表 renti_user_workspace_config）。
 * 存在记录即表示该用户不使用平台默认配置。
 */
@Entity
@Table(name = "user_workspace_config")
@Getter
@Setter
@NoArgsConstructor
public class UserWorkspaceConfigEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config = new LinkedHashMap<>();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}

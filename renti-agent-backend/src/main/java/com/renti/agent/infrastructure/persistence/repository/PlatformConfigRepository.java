package com.renti.agent.infrastructure.persistence.repository;

import com.renti.agent.infrastructure.persistence.entity.PlatformConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 平台配置仓储 */
public interface PlatformConfigRepository extends JpaRepository<PlatformConfigEntity, String> {
}

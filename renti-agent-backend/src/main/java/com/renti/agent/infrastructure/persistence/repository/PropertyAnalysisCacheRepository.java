package com.renti.agent.infrastructure.persistence.repository;

import com.renti.agent.infrastructure.persistence.entity.PropertyAnalysisCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 房源深度分析缓存仓储 */
public interface PropertyAnalysisCacheRepository extends JpaRepository<PropertyAnalysisCacheEntity, String> {
}

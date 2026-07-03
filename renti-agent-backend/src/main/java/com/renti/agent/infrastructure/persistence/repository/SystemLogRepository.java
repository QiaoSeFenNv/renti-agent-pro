package com.renti.agent.infrastructure.persistence.repository;

import com.renti.agent.infrastructure.persistence.entity.SystemLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 系统日志仓储 */
public interface SystemLogRepository
        extends JpaRepository<SystemLogEntity, Long>, JpaSpecificationExecutor<SystemLogEntity> {
}

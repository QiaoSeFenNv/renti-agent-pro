package com.renti.agent.infrastructure.persistence.repository;

import com.renti.agent.infrastructure.persistence.entity.RetrievalAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 检索审计仓储 */
public interface RetrievalAuditRepository
        extends JpaRepository<RetrievalAuditEntity, Long>, JpaSpecificationExecutor<RetrievalAuditEntity> {
}

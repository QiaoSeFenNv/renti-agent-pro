package com.renti.agent.infrastructure.persistence.repository;

import com.renti.agent.infrastructure.persistence.entity.AgentTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Agent trace 仓储 */
public interface AgentTraceRepository
        extends JpaRepository<AgentTraceEntity, Long>, JpaSpecificationExecutor<AgentTraceEntity> {
}

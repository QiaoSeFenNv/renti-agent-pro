package com.renti.agent.infrastructure.persistence.repository;

import com.renti.agent.infrastructure.persistence.entity.UserInteractionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 用户交互记录仓储 */
public interface UserInteractionRepository
        extends JpaRepository<UserInteractionEntity, Long>, JpaSpecificationExecutor<UserInteractionEntity> {
}

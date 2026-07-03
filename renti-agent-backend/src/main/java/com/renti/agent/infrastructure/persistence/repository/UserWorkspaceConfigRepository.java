package com.renti.agent.infrastructure.persistence.repository;

import com.renti.agent.infrastructure.persistence.entity.UserWorkspaceConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户专属工作台配置仓储 */
public interface UserWorkspaceConfigRepository extends JpaRepository<UserWorkspaceConfigEntity, Long> {
}

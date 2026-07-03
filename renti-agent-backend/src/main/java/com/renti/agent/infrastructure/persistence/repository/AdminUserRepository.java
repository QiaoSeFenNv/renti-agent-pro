package com.renti.agent.infrastructure.persistence.repository;

import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.AdminUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 管理员仓储 */
public interface AdminUserRepository extends JpaRepository<AdminUserEntity, Long> {

    Optional<AdminUserEntity> findByUsername(String username);
}

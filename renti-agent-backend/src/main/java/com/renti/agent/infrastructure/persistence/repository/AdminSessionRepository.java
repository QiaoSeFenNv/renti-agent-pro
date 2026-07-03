package com.renti.agent.infrastructure.persistence.repository;

import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.AdminSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 管理员会话仓储 */
public interface AdminSessionRepository extends JpaRepository<AdminSessionEntity, Long> {

    Optional<AdminSessionEntity> findByTokenHash(String tokenHash);

    void deleteByTokenHash(String tokenHash);
}

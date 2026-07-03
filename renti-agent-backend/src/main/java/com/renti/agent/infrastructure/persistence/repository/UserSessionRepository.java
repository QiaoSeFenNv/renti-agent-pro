package com.renti.agent.infrastructure.persistence.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户会话仓储 */
public interface UserSessionRepository extends JpaRepository<UserSessionEntity, Long> {

    Optional<UserSessionEntity> findByTokenHash(String tokenHash);

    void deleteByTokenHash(String tokenHash);

    void deleteByUserId(Long userId);

    void deleteByExpiresAtBefore(OffsetDateTime cutoff);
}

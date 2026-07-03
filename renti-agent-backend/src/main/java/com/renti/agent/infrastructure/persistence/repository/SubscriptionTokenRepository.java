package com.renti.agent.infrastructure.persistence.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.SubscriptionTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 订阅 token 仓储 */
public interface SubscriptionTokenRepository extends JpaRepository<SubscriptionTokenEntity, Long> {

    Optional<SubscriptionTokenEntity>
    findFirstByTokenHashAndPurposeAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String tokenHash, String purpose, OffsetDateTime now);
}

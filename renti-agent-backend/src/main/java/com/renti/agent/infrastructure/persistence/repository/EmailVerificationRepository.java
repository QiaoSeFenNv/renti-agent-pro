package com.renti.agent.infrastructure.persistence.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.EmailVerificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 邮箱验证码仓储 */
public interface EmailVerificationRepository extends JpaRepository<EmailVerificationEntity, Long> {

    List<EmailVerificationEntity> findByUserIdAndConsumedAtIsNull(Long userId);

    Optional<EmailVerificationEntity>
    findFirstByEmailIgnoreCaseAndTokenHashAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String email, String tokenHash, OffsetDateTime now);
}

package com.renti.agent.infrastructure.persistence.repository;

import com.renti.agent.infrastructure.persistence.entity.EmailOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 模拟发信 outbox 仓储 */
public interface EmailOutboxRepository extends JpaRepository<EmailOutboxEntity, Long> {
}

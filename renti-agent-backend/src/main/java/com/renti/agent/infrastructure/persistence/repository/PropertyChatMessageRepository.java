package com.renti.agent.infrastructure.persistence.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.renti.agent.infrastructure.persistence.entity.PropertyChatMessageEntity;

public interface PropertyChatMessageRepository extends JpaRepository<PropertyChatMessageEntity, Long> {

    List<PropertyChatMessageEntity> findTop120BySessionIdOrderByCreatedAtAscIdAsc(Long sessionId);

    void deleteBySessionIdIn(Collection<Long> sessionIds);
}

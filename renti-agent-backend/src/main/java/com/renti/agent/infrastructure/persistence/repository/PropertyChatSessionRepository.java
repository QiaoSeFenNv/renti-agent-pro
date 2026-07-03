package com.renti.agent.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.renti.agent.infrastructure.persistence.entity.PropertyChatSessionEntity;

public interface PropertyChatSessionRepository extends JpaRepository<PropertyChatSessionEntity, Long> {

    List<PropertyChatSessionEntity> findTop50ByUserIdAndListingIdOrderByUpdatedAtDescIdDesc(Long userId, String listingId);

    Optional<PropertyChatSessionEntity> findByIdAndUserId(Long id, Long userId);

    List<PropertyChatSessionEntity> findByUserIdAndListingId(Long userId, String listingId);
}

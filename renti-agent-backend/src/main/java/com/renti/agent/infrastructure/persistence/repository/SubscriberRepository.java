package com.renti.agent.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.SubscriberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 邮箱订阅者仓储 */
public interface SubscriberRepository extends JpaRepository<SubscriberEntity, Long> {

    Optional<SubscriberEntity> findByEmailIgnoreCase(String email);

    @Query("SELECT s.status AS status, COUNT(s) AS count FROM SubscriberEntity s GROUP BY s.status ORDER BY s.status")
    List<StatusCount> countByStatusGrouped();

    interface StatusCount {
        String getStatus();

        long getCount();
    }
}

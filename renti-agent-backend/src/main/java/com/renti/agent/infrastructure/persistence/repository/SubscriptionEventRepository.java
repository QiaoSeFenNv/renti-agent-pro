package com.renti.agent.infrastructure.persistence.repository;

import java.util.List;

import com.renti.agent.infrastructure.persistence.entity.SubscriptionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 订阅事件仓储 */
public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEventEntity, Long> {

    @Query("""
            SELECT e.source AS source, COUNT(e) AS count
            FROM SubscriptionEventEntity e
            WHERE e.eventType = 'request'
            GROUP BY e.source
            ORDER BY COUNT(e) DESC, e.source ASC
            """)
    List<SourceCount> countRequestsBySource();

    interface SourceCount {
        String getSource();

        long getCount();
    }
}

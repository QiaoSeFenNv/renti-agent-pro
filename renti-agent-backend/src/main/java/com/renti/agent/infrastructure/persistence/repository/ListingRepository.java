package com.renti.agent.infrastructure.persistence.repository;

import java.time.OffsetDateTime;
import java.util.List;

import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 已发布房源仓储 */
public interface ListingRepository
        extends JpaRepository<ListingEntity, String>, JpaSpecificationExecutor<ListingEntity> {

    List<ListingEntity> findByCityAndStatus(String city, String status);

    List<ListingEntity> findByStatus(String status);

    Page<ListingEntity> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);

    long countByCityAndStatus(String city, String status);

    /** 待核验：active 且从未核验或核验已过期，最久未核验优先。 */
    @Query("""
            SELECT l FROM ListingEntity l
            WHERE l.status = 'active'
              AND (l.verifiedAt IS NULL OR l.verifiedAt < :staleBefore)
            ORDER BY l.verifiedAt ASC NULLS FIRST
            """)
    List<ListingEntity> findNeedingVerification(@Param("staleBefore") OffsetDateTime staleBefore, Pageable pageable);
}


package com.renti.agent.infrastructure.persistence.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.ListingCandidateEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 采集候选房源仓储 */
public interface ListingCandidateRepository extends JpaRepository<ListingCandidateEntity, Long> {

    Optional<ListingCandidateEntity> findByDedupeKey(String dedupeKey);

    /**
     * 跨源去重：找同一物理指纹、来自其他来源、仍在流转（pending/approved/duplicate 除外的活跃态）的候选，
     * 已发布(approved)优先、其次最早创建者作为主记录。
     */
    @Query(value = """
            SELECT * FROM listing_candidates
            WHERE fingerprint = :fingerprint
              AND fingerprint <> ''
              AND provider <> :provider
              AND status IN ('pending', 'approved')
            ORDER BY CASE status WHEN 'approved' THEN 0 ELSE 1 END, created_at ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ListingCandidateEntity> findCrossSourcePrimary(@Param("fingerprint") String fingerprint,
                                                            @Param("provider") String provider);

    Page<ListingCandidateEntity> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);

    List<ListingCandidateEntity> findByStatusAndJobIdIn(String status, Collection<Long> jobIds);

    /** 坐标回填：查同小区的已有候选（payload 内 community 精确匹配） */
    @Query(value = """
            SELECT * FROM listing_candidates
            WHERE payload ->> 'community' = :community
            ORDER BY updated_at DESC
            LIMIT 50
            """, nativeQuery = true)
    List<ListingCandidateEntity> findRecentByCommunity(@Param("community") String community);
}

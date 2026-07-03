package com.renti.agent.infrastructure.persistence.repository;

import java.util.List;

import com.renti.agent.infrastructure.persistence.entity.IngestionJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 采集任务仓储 */
public interface IngestionJobRepository extends JpaRepository<IngestionJobEntity, Long> {

    List<IngestionJobEntity> findTop6ByOrderByCreatedAtDesc();

    List<IngestionJobEntity> findBySourceNameAndCity(String sourceName, String city);

    /** 每个来源名取最近一条任务，用于 overview 的 sources 概览 */
    @Query(value = """
            SELECT DISTINCT ON (source_name) *
            FROM listing_ingestion_jobs
            ORDER BY source_name, created_at DESC
            """, nativeQuery = true)
    List<IngestionJobEntity> findLatestPerSource();
}

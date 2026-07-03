package com.renti.agent.infrastructure.persistence.repository;

import java.time.OffsetDateTime;
import java.util.List;

import com.renti.agent.infrastructure.persistence.entity.CrawlerScheduleEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 爬虫插件调度配置仓储 */
public interface CrawlerScheduleRepository extends JpaRepository<CrawlerScheduleEntity, String> {

    List<CrawlerScheduleEntity> findAllByOrderByPluginIdAsc();

    @Query("""
            SELECT s FROM CrawlerScheduleEntity s
            WHERE s.enabled = true AND (s.nextRunAt IS NULL OR s.nextRunAt <= :now)
            ORDER BY s.nextRunAt ASC NULLS FIRST, s.updatedAt ASC
            """)
    List<CrawlerScheduleEntity> findDue(@Param("now") OffsetDateTime now, Pageable pageable);
}

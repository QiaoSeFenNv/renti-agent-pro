package com.renti.agent.infrastructure.persistence.repository;

import java.util.List;

import com.renti.agent.infrastructure.persistence.entity.SearchHistoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户搜索历史仓储 */
public interface SearchHistoryRepository extends JpaRepository<SearchHistoryEntity, Long> {

    List<SearchHistoryEntity> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    long deleteByUserId(Long userId);

    long countByUserId(Long userId);
}

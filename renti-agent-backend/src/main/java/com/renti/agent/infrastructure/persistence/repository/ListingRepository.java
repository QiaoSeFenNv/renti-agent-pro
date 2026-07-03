package com.renti.agent.infrastructure.persistence.repository;

import java.util.List;

import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 已发布房源仓储 */
public interface ListingRepository
        extends JpaRepository<ListingEntity, String>, JpaSpecificationExecutor<ListingEntity> {

    List<ListingEntity> findByCityAndStatus(String city, String status);

    List<ListingEntity> findByStatus(String status);

    Page<ListingEntity> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);

    long countByCityAndStatus(String city, String status);
}

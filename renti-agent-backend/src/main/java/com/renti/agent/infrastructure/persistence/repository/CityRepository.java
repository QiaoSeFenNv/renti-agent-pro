package com.renti.agent.infrastructure.persistence.repository;

import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.CityEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 城市仓储 */
public interface CityRepository extends JpaRepository<CityEntity, Long> {

    Optional<CityEntity> findBySlug(String slug);

    Optional<CityEntity> findByName(String name);

    @Query("""
            SELECT c FROM CityEntity c
            WHERE :query = ''
               OR lower(c.name) LIKE lower(concat('%', :query, '%'))
               OR lower(c.slug) LIKE lower(concat('%', :query, '%'))
               OR lower(coalesce(c.nameEn, '')) LIKE lower(concat('%', :query, '%'))
               OR lower(coalesce(c.province, '')) LIKE lower(concat('%', :query, '%'))
               OR coalesce(c.adcode, '') LIKE concat('%', :query, '%')
            ORDER BY c.enabled DESC, c.sortOrder ASC, c.id ASC
            """)
    Page<CityEntity> search(@Param("query") String query, Pageable pageable);
}

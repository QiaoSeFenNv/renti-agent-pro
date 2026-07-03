package com.renti.agent.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.ImportedListingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户自有导入房源仓储 */
public interface ImportedListingRepository extends JpaRepository<ImportedListingEntity, Long> {

    List<ImportedListingEntity> findByUserIdAndCityOrderByUpdatedAtDesc(Long userId, String city);

    Optional<ImportedListingEntity> findByUserIdAndCityAndListingId(Long userId, String city, String listingId);

    long deleteByUserIdAndCity(Long userId, String city);
}

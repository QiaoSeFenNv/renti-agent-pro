package com.renti.agent.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.UserFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户收藏仓储 */
public interface UserFavoriteRepository extends JpaRepository<UserFavoriteEntity, Long> {

    List<UserFavoriteEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<UserFavoriteEntity> findByUserIdAndListingId(Long userId, String listingId);

    long deleteByUserIdAndListingId(Long userId, String listingId);
}

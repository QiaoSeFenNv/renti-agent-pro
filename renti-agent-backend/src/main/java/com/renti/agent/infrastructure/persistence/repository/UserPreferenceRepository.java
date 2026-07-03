package com.renti.agent.infrastructure.persistence.repository;

import com.renti.agent.infrastructure.persistence.entity.UserPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户租房偏好仓储 */
public interface UserPreferenceRepository extends JpaRepository<UserPreferenceEntity, Long> {
}

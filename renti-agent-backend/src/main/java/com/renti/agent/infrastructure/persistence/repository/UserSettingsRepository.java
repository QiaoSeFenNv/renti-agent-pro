package com.renti.agent.infrastructure.persistence.repository;

import com.renti.agent.infrastructure.persistence.entity.UserSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户工作台设置仓储 */
public interface UserSettingsRepository extends JpaRepository<UserSettingsEntity, Long> {
}

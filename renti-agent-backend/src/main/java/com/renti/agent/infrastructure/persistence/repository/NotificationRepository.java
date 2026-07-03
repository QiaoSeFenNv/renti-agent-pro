package com.renti.agent.infrastructure.persistence.repository;

import java.util.List;

import com.renti.agent.infrastructure.persistence.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 平台公告仓储 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    List<NotificationEntity> findAllByOrderByCreatedAtDescIdDesc();

    List<NotificationEntity> findByPublishedTrueOrderByCreatedAtDescIdDesc();
}

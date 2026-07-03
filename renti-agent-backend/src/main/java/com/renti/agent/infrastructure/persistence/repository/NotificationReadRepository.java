package com.renti.agent.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.NotificationReadEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户公告已读记录仓储 */
public interface NotificationReadRepository extends JpaRepository<NotificationReadEntity, Long> {

    List<NotificationReadEntity> findByUserId(Long userId);

    Optional<NotificationReadEntity> findByUserIdAndNotificationId(Long userId, Long notificationId);
}

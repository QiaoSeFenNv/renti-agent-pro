package com.renti.agent.modules.notification.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.renti.agent.infrastructure.persistence.entity.NotificationEntity;
import com.renti.agent.infrastructure.persistence.entity.NotificationReadEntity;
import com.renti.agent.infrastructure.persistence.repository.NotificationReadRepository;
import com.renti.agent.infrastructure.persistence.repository.NotificationRepository;
import com.renti.agent.modules.notification.application.dto.NotificationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台公告：用户端（已发布公告 + 已读状态）与管理端 CRUD。
 * 响应结构对齐旧版 services/notifications.py。
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Set<String> NOTIFICATION_TONES = Set.of("info", "success", "warning");

    private final NotificationRepository notificationRepository;
    private final NotificationReadRepository notificationReadRepository;

    // -------------------------------------------------------------------- user

    /** GET /api/user/notifications */
    @Transactional(readOnly = true)
    public Map<String, Object> userListPayload(Long userId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.putAll(listForUser(userId));
        return body;
    }

    /** POST /api/user/notifications/read-all */
    @Transactional
    public Map<String, Object> markAllReadPayload(Long userId) {
        var readIds = readAtByNotificationId(userId).keySet();
        for (NotificationEntity notification : notificationRepository.findByPublishedTrueOrderByCreatedAtDescIdDesc()) {
            if (!readIds.contains(notification.getId())) {
                saveRead(userId, notification.getId());
            }
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("summary", "所有通知已标记为已读。");
        body.putAll(listForUser(userId));
        return body;
    }

    /** POST /api/user/notifications/{id}/read */
    @Transactional
    public Map<String, Object> markReadPayload(Long userId, Long notificationId) {
        var notification = notificationRepository.findById(notificationId)
                .filter(NotificationEntity::isPublished)
                .orElse(null);
        if (notification == null) {
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", false);
            body.put("code", "not_found");
            body.put("summary", "通知不存在或未发布。");
            return body;
        }
        if (notificationReadRepository.findByUserIdAndNotificationId(userId, notificationId).isEmpty()) {
            saveRead(userId, notificationId);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("summary", "通知已标记为已读。");
        body.putAll(listForUser(userId));
        return body;
    }

    // ------------------------------------------------------------------- admin

    /** GET /api/admin/notifications */
    @Transactional(readOnly = true)
    public Map<String, Object> adminListPayload() {
        var rows = notificationRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(item -> toMap(item, null))
                .toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("notifications", rows);
        body.put("total", rows.size());
        return body;
    }

    /** POST /api/admin/notifications */
    @Transactional
    public Map<String, Object> adminCreatePayload(NotificationRequest request) {
        var validation = validate(request);
        if (validation.error != null) {
            return validation.error;
        }
        var notification = new NotificationEntity();
        applyValues(notification, validation);
        notificationRepository.save(notification);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("notification", toMap(notification, null));
        body.put("summary", "通知已发布。");
        return body;
    }

    /** PUT /api/admin/notifications/{id} */
    @Transactional
    public Map<String, Object> adminUpdatePayload(Long notificationId, NotificationRequest request) {
        var validation = validate(request);
        if (validation.error != null) {
            return validation.error;
        }
        var notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return error("not_found", "通知不存在。");
        }
        applyValues(notification, validation);
        notification.setUpdatedAt(OffsetDateTime.now());
        notificationRepository.save(notification);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("notification", toMap(notification, null));
        body.put("summary", "通知已更新。");
        return body;
    }

    /** DELETE /api/admin/notifications/{id} */
    @Transactional
    public Map<String, Object> adminDeletePayload(Long notificationId) {
        if (!notificationRepository.existsById(notificationId)) {
            return error("not_found", "通知不存在。");
        }
        notificationRepository.deleteById(notificationId);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("deleted", true);
        body.put("summary", "通知已删除。");
        return body;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> listForUser(Long userId) {
        var readAtById = readAtByNotificationId(userId);
        var rows = new ArrayList<Map<String, Object>>();
        for (NotificationEntity item : notificationRepository.findByPublishedTrueOrderByCreatedAtDescIdDesc()) {
            rows.add(toMap(item, readAtById.get(item.getId())));
        }
        var unreadCount = rows.stream().filter(row -> !Boolean.TRUE.equals(row.get("isRead"))).count();
        var result = new LinkedHashMap<String, Object>();
        result.put("notifications", rows);
        result.put("unreadCount", unreadCount);
        result.put("total", rows.size());
        return result;
    }

    private Map<Long, OffsetDateTime> readAtByNotificationId(Long userId) {
        var result = new HashMap<Long, OffsetDateTime>();
        for (NotificationReadEntity read : notificationReadRepository.findByUserId(userId)) {
            result.put(read.getNotificationId(), read.getReadAt());
        }
        return result;
    }

    private void saveRead(Long userId, Long notificationId) {
        var read = new NotificationReadEntity();
        read.setUserId(userId);
        read.setNotificationId(notificationId);
        notificationReadRepository.save(read);
    }

    private Map<String, Object> toMap(NotificationEntity entity, OffsetDateTime readAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", entity.getId());
        row.put("title", entity.getTitle());
        row.put("body", entity.getBody());
        row.put("tone", entity.getTone());
        row.put("published", entity.isPublished());
        row.put("createdAt", entity.getCreatedAt());
        row.put("updatedAt", entity.getUpdatedAt());
        row.put("readAt", readAt);
        row.put("isRead", readAt != null);
        return row;
    }

    private record ValidatedValues(String title, String body, String tone, boolean published,
                                   Map<String, Object> error) {
    }

    private ValidatedValues validate(NotificationRequest request) {
        var title = cleanText(request == null ? null : request.title(), 120);
        var body = cleanText(request == null ? null : request.body(), 1200);
        var tone = request == null || request.tone() == null || request.tone().isBlank()
                ? "info" : request.tone().strip().toLowerCase();

        var fieldErrors = new LinkedHashMap<String, String>();
        if (title.isEmpty()) {
            fieldErrors.put("title", "请填写通知标题。");
        }
        if (body.isEmpty()) {
            fieldErrors.put("body", "请填写通知内容。");
        }
        if (!NOTIFICATION_TONES.contains(tone)) {
            fieldErrors.put("tone", "通知类型只能是 info / success / warning。");
        }
        if (!fieldErrors.isEmpty()) {
            var error = error("invalid_input", "请检查通知内容。");
            error.put("fieldErrors", fieldErrors);
            return new ValidatedValues(null, null, null, false, error);
        }
        return new ValidatedValues(title, body, tone,
                boolValue(request == null ? null : request.published(), true), null);
    }

    private void applyValues(NotificationEntity notification, ValidatedValues values) {
        notification.setTitle(values.title());
        notification.setBody(values.body());
        notification.setTone(values.tone());
        notification.setPublished(values.published());
    }

    private LinkedHashMap<String, Object> error(String code, String summary) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("code", code);
        body.put("summary", summary);
        body.put("fieldErrors", Map.of());
        return body;
    }

    private String cleanText(String value, int limit) {
        var collapsed = String.join(" ", (value == null ? "" : value).strip().split("\\s+"));
        return collapsed.length() > limit ? collapsed.substring(0, limit) : collapsed;
    }

    private boolean boolValue(Object value, boolean fallback) {
        if (value == null || "".equals(value)) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return List.of("1", "true", "yes", "on").contains(String.valueOf(value).strip().toLowerCase());
    }
}

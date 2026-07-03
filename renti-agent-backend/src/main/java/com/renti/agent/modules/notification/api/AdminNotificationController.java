package com.renti.agent.modules.notification.api;

import java.util.Map;

import com.renti.agent.modules.notification.application.NotificationService;
import com.renti.agent.modules.notification.application.dto.NotificationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端公告 CRUD。路径受 SessionAuthInterceptor 管理员会话保护。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public Map<String, Object> list() {
        return notificationService.adminListPayload();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody NotificationRequest request) {
        var result = notificationService.adminCreatePayload(request);
        log.info("admin notification create ok={}", result.get("ok"));
        return result;
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody NotificationRequest request) {
        var result = notificationService.adminUpdatePayload(id, request);
        log.info("admin notification update id={} ok={}", id, result.get("ok"));
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        var result = notificationService.adminDeletePayload(id);
        log.info("admin notification delete id={} ok={}", id, result.get("ok"));
        return result;
    }
}

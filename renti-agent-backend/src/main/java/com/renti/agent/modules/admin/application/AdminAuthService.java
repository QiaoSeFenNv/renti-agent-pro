package com.renti.agent.modules.admin.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.renti.agent.common.config.RentiProperties;
import com.renti.agent.infrastructure.persistence.entity.AdminUserEntity;
import com.renti.agent.infrastructure.persistence.repository.AdminSessionRepository;
import com.renti.agent.infrastructure.persistence.repository.AdminUserRepository;
import com.renti.agent.modules.auth.application.PasswordService;
import com.renti.agent.modules.auth.application.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员登录/会话/登出。响应结构对齐旧 services/admin.py
 * （admin_login_payload / admin_session_payload / admin_logout_payload）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final AdminSessionRepository adminSessionRepository;
    private final SessionService sessionService;
    private final PasswordService passwordService;
    private final RentiProperties properties;

    /** POST /api/admin/login：成功写 Cookie，失败返回 ok:false（HTTP 200，对齐旧版） */
    @Transactional
    public Map<String, Object> login(String username, String password, HttpServletResponse response) {
        var cleanUsername = username == null ? "" : username.strip();
        var cleanPassword = password == null ? "" : password;
        if (cleanUsername.isEmpty() || cleanPassword.length() < 8) {
            return error("invalid_input", "请检查管理员账号和密码。");
        }

        var admin = adminUserRepository.findByUsername(cleanUsername).orElse(null);
        if (admin == null || !passwordService.verify(cleanPassword, admin.getPasswordAlgo(),
                admin.getPasswordHash(), admin.getPasswordSalt(), admin.getPasswordIterations())) {
            return error("invalid_credentials", "管理员账号或密码不正确。");
        }

        long ttlSeconds = properties.security().adminSessionTtlSeconds();
        var token = sessionService.createAdminSession(admin.getId());
        admin.setLastLoginAt(OffsetDateTime.now());
        adminUserRepository.save(admin);
        sessionService.writeSessionCookie(response, SessionService.ADMIN_COOKIE, token, ttlSeconds);
        log.info("Admin '{}' logged in", cleanUsername);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("admin", publicAdmin(admin));
        body.put("expiresAt", OffsetDateTime.now().plusSeconds(ttlSeconds).toEpochSecond());
        return body;
    }

    /** GET /api/admin/session */
    @Transactional(readOnly = true)
    public Map<String, Object> session(HttpServletRequest request) {
        var token = sessionService.extractToken(request, SessionService.ADMIN_COOKIE);
        var body = new LinkedHashMap<String, Object>();
        if (token == null || token.isBlank()) {
            body.put("authenticated", false);
            return body;
        }
        var session = adminSessionRepository.findByTokenHash(passwordService.sha256Hex(token))
                .filter(item -> item.getExpiresAt().isAfter(OffsetDateTime.now()))
                .orElse(null);
        var admin = session == null ? null
                : adminUserRepository.findById(session.getAdminId()).orElse(null);
        if (admin == null) {
            body.put("authenticated", false);
            return body;
        }
        body.put("authenticated", true);
        body.put("admin", publicAdmin(admin));
        body.put("expiresAt", session.getExpiresAt().toEpochSecond());
        return body;
    }

    /** POST /api/admin/logout：吊销会话并清 Cookie */
    @Transactional
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        var token = sessionService.extractToken(request, SessionService.ADMIN_COOKIE);
        sessionService.revokeAdminSession(token);
        sessionService.clearSessionCookie(response, SessionService.ADMIN_COOKIE);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("summary", "管理员已退出。");
        return body;
    }

    private Map<String, Object> publicAdmin(AdminUserEntity admin) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", admin.getId());
        value.put("username", admin.getUsername());
        value.put("displayName", admin.getDisplayName() == null || admin.getDisplayName().isBlank()
                ? admin.getUsername() : admin.getDisplayName());
        return value;
    }

    private Map<String, Object> error(String code, String summary) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("code", code);
        body.put("summary", summary);
        body.put("fieldErrors", Map.of());
        return body;
    }
}

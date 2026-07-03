package com.renti.agent.modules.auth.application;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.renti.agent.common.config.RentiProperties;
import com.renti.agent.infrastructure.persistence.entity.AdminSessionEntity;
import com.renti.agent.infrastructure.persistence.entity.UserSessionEntity;
import com.renti.agent.infrastructure.persistence.repository.AdminSessionRepository;
import com.renti.agent.infrastructure.persistence.repository.AdminUserRepository;
import com.renti.agent.infrastructure.persistence.repository.UserRepository;
import com.renti.agent.infrastructure.persistence.repository.UserSessionRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话服务：用户/管理员双通道 Cookie 会话的创建、解析与吊销。
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    public static final String USER_COOKIE = "renti_session";
    public static final String ADMIN_COOKIE = "renti_admin_session";

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final AdminUserRepository adminUserRepository;
    private final AdminSessionRepository adminSessionRepository;
    private final PasswordService passwordService;
    private final RentiProperties properties;

    /** 创建用户会话并返回 token 原文 */
    @Transactional
    public String createUserSession(Long userId, String clientId) {
        var token = passwordService.generateToken();
        var session = new UserSessionEntity();
        session.setUserId(userId);
        session.setTokenHash(passwordService.sha256Hex(token));
        session.setClientId(clientId);
        session.setExpiresAt(OffsetDateTime.now().plusSeconds(properties.security().sessionTtlSeconds()));
        userSessionRepository.save(session);
        return token;
    }

    @Transactional
    public String createAdminSession(Long adminId) {
        var token = passwordService.generateToken();
        var session = new AdminSessionEntity();
        session.setAdminId(adminId);
        session.setTokenHash(passwordService.sha256Hex(token));
        session.setExpiresAt(OffsetDateTime.now().plusSeconds(properties.security().adminSessionTtlSeconds()));
        adminSessionRepository.save(session);
        return token;
    }

    /** 由 Cookie token 解析当前用户（过期/不存在返回 empty） */
    @Transactional(readOnly = true)
    public Optional<UserPrincipal> resolveUser(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findByTokenHash(passwordService.sha256Hex(token))
                .filter(session -> session.getExpiresAt().isAfter(OffsetDateTime.now()))
                .flatMap(session -> userRepository.findById(session.getUserId()))
                .filter(user -> "active".equals(user.getStatus()))
                .map(user -> new UserPrincipal(
                        user.getId(), user.getEmail(), user.getNickname(), user.isEmailVerified()));
    }

    @Transactional(readOnly = true)
    public Optional<AdminPrincipal> resolveAdmin(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return adminSessionRepository.findByTokenHash(passwordService.sha256Hex(token))
                .filter(session -> session.getExpiresAt().isAfter(OffsetDateTime.now()))
                .flatMap(session -> adminUserRepository.findById(session.getAdminId()))
                .map(admin -> new AdminPrincipal(admin.getId(), admin.getUsername(), admin.getDisplayName()));
    }

    @Transactional
    public void revokeUserSession(String token) {
        if (token != null && !token.isBlank()) {
            userSessionRepository.deleteByTokenHash(passwordService.sha256Hex(token));
        }
    }

    @Transactional
    public void revokeAdminSession(String token) {
        if (token != null && !token.isBlank()) {
            adminSessionRepository.deleteByTokenHash(passwordService.sha256Hex(token));
        }
    }

    /** 从请求 Cookie 中提取 token 原文 */
    public String extractToken(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** 写入会话 Cookie（HttpOnly + SameSite=Lax） */
    public void writeSessionCookie(HttpServletResponse response, String cookieName, String token, long maxAgeSeconds) {
        response.addHeader("Set-Cookie", "%s=%s; Max-Age=%d; Path=/; HttpOnly; SameSite=Lax%s".formatted(
                cookieName, token, maxAgeSeconds, properties.security().cookieSecure() ? "; Secure" : ""));
    }

    /** 清除会话 Cookie */
    public void clearSessionCookie(HttpServletResponse response, String cookieName) {
        writeSessionCookie(response, cookieName, "", 0);
    }
}

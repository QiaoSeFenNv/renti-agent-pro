package com.renti.agent.modules.auth.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentUser;
import com.renti.agent.common.config.RentiProperties;
import com.renti.agent.modules.auth.application.AuthService;
import com.renti.agent.modules.auth.application.SessionService;
import com.renti.agent.modules.auth.application.UserPrincipal;
import com.renti.agent.modules.auth.application.dto.ChangePasswordRequest;
import com.renti.agent.modules.auth.application.dto.LoginRequest;
import com.renti.agent.modules.auth.application.dto.RegisterRequest;
import com.renti.agent.modules.auth.application.dto.SessionRequest;
import com.renti.agent.modules.auth.application.dto.VerifyEmailRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户认证接口（注册/验证/登录/会话/登出/改密/偏好清除）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;
    private final RentiProperties properties;

    @PostMapping("/api/auth/register")
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        var result = authService.register(request);
        log.info("auth register email={} ok={}", request.email(), result.get("ok"));
        return result;
    }

    @PostMapping("/api/auth/verify-email")
    public Map<String, Object> verifyEmail(@RequestBody VerifyEmailRequest request) {
        var result = authService.verifyEmail(request);
        log.info("auth verify-email email={} ok={}", request.email(), result.get("ok"));
        return result;
    }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest request,
                                     HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) {
        var outcome = authService.login(request, clientId(httpRequest));
        if (Boolean.TRUE.equals(outcome.body().get("ok")) && outcome.sessionToken() != null) {
            sessionService.writeSessionCookie(httpResponse, SessionService.USER_COOKIE,
                    outcome.sessionToken(), properties.security().sessionTtlSeconds());
        }
        log.info("auth login email={} ok={}", request.email(), outcome.body().get("ok"));
        return outcome.body();
    }

    @GetMapping("/api/auth/session")
    public Map<String, Object> sessionGet(HttpServletRequest request) {
        return authService.session(sessionService.extractToken(request, SessionService.USER_COOKIE));
    }

    @PostMapping("/api/auth/session")
    public Map<String, Object> sessionPost(@RequestBody(required = false) SessionRequest body,
                                           HttpServletRequest request) {
        var token = sessionService.extractToken(request, SessionService.USER_COOKIE);
        if ((token == null || token.isBlank()) && body != null) {
            token = body.anyToken();
        }
        return authService.session(token);
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        var result = authService.logout(sessionService.extractToken(request, SessionService.USER_COOKIE));
        sessionService.clearSessionCookie(response, SessionService.USER_COOKIE);
        return result;
    }

    @PostMapping("/api/auth/change-password")
    public Map<String, Object> changePassword(@CurrentUser UserPrincipal user,
                                              @RequestBody ChangePasswordRequest request) {
        var result = authService.changePassword(user.id(), request);
        log.info("auth change-password userId={} ok={}", user.id(), result.get("ok"));
        return result;
    }

    @DeleteMapping("/api/user/preferences")
    public Map<String, Object> clearPreferences(@CurrentUser UserPrincipal user) {
        var result = authService.clearPreferences(user.id());
        log.info("auth clear-preferences userId={} ok={}", user.id(), result.get("ok"));
        return result;
    }

    private String clientId(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        var remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "local" : remote;
    }
}

package com.renti.agent.modules.admin.api;

import java.util.Map;

import com.renti.agent.modules.admin.application.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员登录/会话/登出。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    /** 登录请求（兼容 username/email 字段名） */
    public record AdminLoginRequest(String username, String email, String password) {

        public String account() {
            return username != null && !username.isBlank() ? username : email;
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody AdminLoginRequest request, HttpServletResponse response) {
        return adminAuthService.login(request.account(), request.password(), response);
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        return adminAuthService.session(request);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        return adminAuthService.logout(request, response);
    }
}

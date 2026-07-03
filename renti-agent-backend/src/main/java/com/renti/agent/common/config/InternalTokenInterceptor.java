package com.renti.agent.common.config;

import com.renti.agent.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部端点令牌校验：/internal/** 仅供 Python Agent 服务回调，
 * 通过共享密钥 X-Internal-Token 认证。
 */
@Component
@RequiredArgsConstructor
public class InternalTokenInterceptor implements HandlerInterceptor {

    private final RentiProperties properties;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        var token = request.getHeader("X-Internal-Token");
        if (token == null || !token.equals(properties.security().internalToken())) {
            throw BusinessException.unauthorized("internal_token_invalid", "内部令牌无效。");
        }
        return true;
    }
}

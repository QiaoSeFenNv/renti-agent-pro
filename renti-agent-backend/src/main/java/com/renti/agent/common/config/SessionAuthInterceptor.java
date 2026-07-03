package com.renti.agent.common.config;

import java.util.List;

import com.renti.agent.common.exception.BusinessException;
import com.renti.agent.modules.auth.application.AdminPrincipal;
import com.renti.agent.modules.auth.application.SessionService;
import com.renti.agent.modules.auth.application.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;

import com.renti.agent.common.annotation.CurrentAdmin;
import com.renti.agent.common.annotation.CurrentUser;

/**
 * 会话认证拦截器：按路径前缀强制登录，并把解析出的主体放入请求属性。
 *
 * <p>路径规则见 docs/CONVENTIONS.md §5。未登录用户访问受保护接口返回 401
 * （code=authentication_required / admin_authentication_required，与旧版一致）。</p>
 */
@Component
@RequiredArgsConstructor
public class SessionAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER = "renti.currentUser";
    public static final String ATTR_ADMIN = "renti.currentAdmin";

    private static final List<String> USER_PROTECTED_PREFIXES = List.of(
            "/api/user/", "/api/search/", "/api/agent/", "/api/listings/", "/api/places/",
            "/api/locations/", "/api/requirements/", "/api/recommendations/");
    private static final List<String> ADMIN_PUBLIC_PATHS = List.of(
            "/api/admin/login", "/api/admin/session", "/api/admin/logout");

    private final SessionService sessionService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        var path = request.getRequestURI();

        var userToken = sessionService.extractToken(request, SessionService.USER_COOKIE);
        sessionService.resolveUser(userToken).ifPresent(user -> request.setAttribute(ATTR_USER, user));

        var adminToken = sessionService.extractToken(request, SessionService.ADMIN_COOKIE);
        sessionService.resolveAdmin(adminToken).ifPresent(admin -> request.setAttribute(ATTR_ADMIN, admin));

        if (requiresUser(path) && request.getAttribute(ATTR_USER) == null) {
            throw BusinessException.unauthorized("authentication_required", "请先登录后再使用地图和房源接口。");
        }
        if (requiresAdmin(path) && request.getAttribute(ATTR_ADMIN) == null) {
            throw BusinessException.unauthorized("admin_authentication_required", "请先登录后台管理系统。");
        }
        return true;
    }

    private boolean requiresUser(String path) {
        if ("/api/auth/change-password".equals(path)) {
            return true;
        }
        return USER_PROTECTED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private boolean requiresAdmin(String path) {
        return path.startsWith("/api/admin/") && !ADMIN_PUBLIC_PATHS.contains(path);
    }

    /** {@link CurrentUser} 参数解析器 */
    @Component
    public static class CurrentUserResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CurrentUser.class)
                    && parameter.getParameterType().equals(UserPrincipal.class);
        }

        @Override
        public Object resolveArgument(@NonNull MethodParameter parameter,
                                      ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest,
                                      WebDataBinderFactory binderFactory) {
            var user = webRequest.getAttribute(ATTR_USER, NativeWebRequest.SCOPE_REQUEST);
            if (user == null) {
                throw BusinessException.unauthorized("authentication_required", "请先登录后再使用地图和房源接口。");
            }
            return user;
        }
    }

    /** {@link CurrentAdmin} 参数解析器 */
    @Component
    public static class CurrentAdminResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CurrentAdmin.class)
                    && parameter.getParameterType().equals(AdminPrincipal.class);
        }

        @Override
        public Object resolveArgument(@NonNull MethodParameter parameter,
                                      ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest,
                                      WebDataBinderFactory binderFactory) {
            var admin = webRequest.getAttribute(ATTR_ADMIN, NativeWebRequest.SCOPE_REQUEST);
            if (admin == null) {
                throw BusinessException.unauthorized("admin_authentication_required", "请先登录后台管理系统。");
            }
            return admin;
        }
    }
}

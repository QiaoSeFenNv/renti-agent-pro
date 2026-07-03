package com.renti.agent.common.config;

import java.io.IOException;
import java.util.LinkedHashMap;

import com.renti.agent.infrastructure.persistence.entity.SystemLogEntity;
import com.renti.agent.infrastructure.persistence.repository.SystemLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API 请求日志过滤器：/api/** 每个请求落一条 system_logs(kind=api)，
 * 与旧版 FastAPI 中间件行为一致（method/path/status/durationMs/client/error）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiRequestLogFilter extends OncePerRequestFilter {

    private final SystemLogRepository systemLogRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        long started = System.nanoTime();
        String error = "";
        try {
            filterChain.doFilter(request, response);
        } catch (Exception exception) {
            error = exception.getClass().getSimpleName();
            throw exception;
        } finally {
            int status = response.getStatus();
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            persistQuietly(request, status, durationMs, error);
        }
    }

    private void persistQuietly(HttpServletRequest request, int status, long durationMs, String error) {
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("event", "api.request");
            payload.put("method", request.getMethod());
            payload.put("path", request.getRequestURI());
            payload.put("statusCode", status);
            payload.put("durationMs", durationMs);
            payload.put("client", request.getRemoteAddr());
            if (!error.isEmpty()) {
                payload.put("error", error);
            }

            var entry = new SystemLogEntity();
            entry.setKind("api");
            entry.setLevel(status >= 500 ? "error" : "info");
            entry.setMessage("%s %s -> %d (%dms)".formatted(
                    request.getMethod(), request.getRequestURI(), status, durationMs));
            entry.setPayload(payload);
            systemLogRepository.save(entry);
        } catch (Exception exception) {
            // 日志落库失败不能影响业务请求
            log.warn("Failed to persist api log: {}", exception.getMessage());
        }
    }
}

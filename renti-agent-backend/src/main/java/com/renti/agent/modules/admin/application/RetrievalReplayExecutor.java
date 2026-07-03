package com.renti.agent.modules.admin.application;

import java.util.Map;

/**
 * 检索回放执行器：由 search/agent 模块提供实现，用审计里存的请求按当前配置重跑检索。
 *
 * <p>约定：不支持回放的 endpoint 返回 {@code null}；否则返回与原端点一致的响应 payload
 * （含 recommendations/matches、total、toolTrace 等字段）。</p>
 */
@FunctionalInterface
public interface RetrievalReplayExecutor {

    /**
     * @param endpoint       原审计端点（如 /api/search/map-intent、/api/agent/rental-search）
     * @param userId         原审计用户 ID（可能为 null）
     * @param requestPayload 原请求 payload（已注入 retrievalAuditEnabled=false 防止递归记录）
     * @return 回放响应 payload；不支持该 endpoint 时返回 null
     */
    Map<String, Object> replay(String endpoint, Long userId, Map<String, Object> requestPayload);
}

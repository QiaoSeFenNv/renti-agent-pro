package com.renti.agent.modules.admin.application;

import java.util.LinkedHashMap;
import java.util.Map;

import com.renti.agent.infrastructure.persistence.repository.AgentTraceRepository;
import com.renti.agent.infrastructure.persistence.repository.ListingRepository;
import com.renti.agent.infrastructure.persistence.repository.RetrievalAuditRepository;
import com.renti.agent.infrastructure.persistence.repository.SubscriberRepository;
import com.renti.agent.infrastructure.persistence.repository.UserFavoriteRepository;
import com.renti.agent.infrastructure.persistence.repository.UserInteractionRepository;
import com.renti.agent.infrastructure.persistence.repository.UserRepository;
import com.renti.agent.modules.user.application.WorkspaceConfigService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台总览统计。对齐旧 admin.py overview 的 {ok, counts, platformConfig} 结构，
 * 并补充房源/候选/观测记录计数（候选表由 ingestion 模块并行建设，缺表时兜底 0）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private final UserRepository userRepository;
    private final UserFavoriteRepository userFavoriteRepository;
    private final SubscriberRepository subscriberRepository;
    private final ListingRepository listingRepository;
    private final AgentTraceRepository agentTraceRepository;
    private final UserInteractionRepository userInteractionRepository;
    private final RetrievalAuditRepository retrievalAuditRepository;
    private final WorkspaceConfigService workspaceConfigService;
    private final EntityManager entityManager;

    /** GET /api/admin/overview 响应 */
    @Transactional(readOnly = true)
    public Map<String, Object> overviewPayload() {
        var counts = new LinkedHashMap<String, Object>();
        counts.put("users", userRepository.count());
        counts.put("verifiedUsers", jpqlCount(
                "SELECT COUNT(u) FROM UserEntity u WHERE u.emailVerified = TRUE"));
        counts.put("favorites", userFavoriteRepository.count());
        counts.put("searchHistory", jpqlCount("SELECT COUNT(h) FROM SearchHistoryEntity h"));
        counts.put("subscribers", subscriberRepository.count());
        counts.put("activeUserSessions", jpqlCount(
                "SELECT COUNT(s) FROM UserSessionEntity s WHERE s.expiresAt > CURRENT_TIMESTAMP"));
        counts.put("listings", listingRepository.count());
        counts.put("candidates", nativeCountOrZero("listing_candidates"));
        counts.put("agentTraces", agentTraceRepository.count());
        counts.put("userInteractions", userInteractionRepository.count());
        counts.put("retrievalAudits", retrievalAuditRepository.count());

        var platformConfig = new LinkedHashMap<String, Object>();
        platformConfig.put("ok", true);
        platformConfig.putAll(workspaceConfigService.config());

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("counts", counts);
        body.put("platformConfig", platformConfig);
        return body;
    }

    private long jpqlCount(String jpql) {
        var result = entityManager.createQuery(jpql, Long.class).getSingleResult();
        return result == null ? 0 : result;
    }

    /** 目标表可能由并行模块建设中：先探测表存在再计数，缺表兜底 0（避免事务被异常打断） */
    private long nativeCountOrZero(String table) {
        try {
            var exists = entityManager
                    .createNativeQuery("SELECT to_regclass(:table) IS NOT NULL", Boolean.class)
                    .setParameter("table", table)
                    .getSingleResult();
            if (!Boolean.TRUE.equals(exists)) {
                return 0;
            }
            var count = (Number) entityManager
                    .createNativeQuery("SELECT count(*) FROM " + table)
                    .getSingleResult();
            return count == null ? 0 : count.longValue();
        } catch (Exception exception) {
            log.debug("Count fallback for table {}: {}", table, exception.getMessage());
            return 0;
        }
    }
}

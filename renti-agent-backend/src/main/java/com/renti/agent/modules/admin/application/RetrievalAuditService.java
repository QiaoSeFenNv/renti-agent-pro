package com.renti.agent.modules.admin.application;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.renti.agent.common.exception.BusinessException;
import com.renti.agent.infrastructure.persistence.entity.RetrievalAuditEntity;
import com.renti.agent.infrastructure.persistence.repository.RetrievalAuditRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.renti.agent.modules.admin.application.ObservabilitySupport.asList;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.asMap;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.boundedInt;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.hitsFromResponse;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.str;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.stripBlockedKeys;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.totalHits;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.totalPages;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.truncate;

/**
 * 检索审计：写入（供 search/agent 模块调用）、管理端查询与回放。
 * 行为对齐旧 services/retrieval_audit.py。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalAuditService {

    private static final Set<String> BLOCKED_REQUEST_KEYS = Set.of(
            "password", "token", "authorization", "api_key", "apikey", "jina_apikey", "embeddingapikey");

    private final RetrievalAuditRepository retrievalAuditRepository;
    private final ObjectProvider<RetrievalReplayExecutor> replayExecutorProvider;

    /** 记录一次检索审计（对齐旧 record_retrieval_audit + build_retrieval_audit_values）；失败只打日志 */
    @Transactional
    public void record(Long userId, String endpoint, Map<String, Object> requestPayload,
                       Map<String, Object> responsePayload, long durationMs) {
        var request = requestPayload == null ? Map.<String, Object>of() : requestPayload;
        if (Boolean.FALSE.equals(request.get("retrievalAuditEnabled"))) {
            return;
        }
        try {
            var response = responsePayload == null ? Map.<String, Object>of() : responsePayload;
            var parsed = asMap(response.get("parsed"));

            var entity = new RetrievalAuditEntity();
            entity.setUserId(userId);
            entity.setEndpoint(truncate(str(endpoint), 160));
            entity.setQueryText(queryText(request, response));
            entity.setCity(truncate(firstNonEmpty(str(request.get("city")), str(parsed.get("city")), "上海"), 40));
            entity.setDurationMs((int) Math.max(0, durationMs));
            entity.setTotalHits(totalHits(response));
            entity.setRequestPayload(stripBlockedKeys(request, BLOCKED_REQUEST_KEYS));
            entity.setHits(hitsFromResponse(response));
            entity.setToolTrace(response.get("toolTrace") instanceof List<?> ? asList(response.get("toolTrace")) : List.of());
            entity.setResponseSummary(truncate(str(response.get("summary")), 2000));
            retrievalAuditRepository.save(entity);
        } catch (Exception exception) {
            log.debug("Retrieval audit recording failed: {}", exception.getMessage());
        }
    }

    /** GET /api/admin/retrieval-audits 响应 */
    @Transactional(readOnly = true)
    public Map<String, Object> listPayload(Integer limit, Integer page, Long userId, String endpoint) {
        int pageSize = boundedInt(limit, 50, 1, 200);
        int pageNumber = boundedInt(page, 1, 1, 100000);
        var cleanEndpoint = truncate(str(endpoint).strip(), 160);

        Specification<RetrievalAuditEntity> spec = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (!cleanEndpoint.isEmpty()) {
                predicates.add(cb.equal(root.get("endpoint"), cleanEndpoint));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(pageNumber - 1, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var result = retrievalAuditRepository.findAll(spec, pageable);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("audits", result.getContent().stream().map(entity -> toMap(entity, false)).toList());
        body.put("total", result.getTotalElements());
        body.put("page", pageNumber);
        body.put("pageSize", pageSize);
        body.put("totalPages", totalPages(result.getTotalElements(), pageSize));
        return body;
    }

    /** GET /api/admin/retrieval-audits/{id} 响应，缺失时 404 */
    @Transactional(readOnly = true)
    public Map<String, Object> detailPayload(long auditId) {
        var entity = retrievalAuditRepository.findById(auditId)
                .orElseThrow(() -> BusinessException.notFound("召回审计记录不存在。"));
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("audit", toMap(entity, true));
        return body;
    }

    /**
     * POST /api/admin/retrieval-audits/{id}/replay：用审计里存的请求按当前配置重跑检索。
     * 回放执行器由 search 模块提供；未接入时返回 replay_unavailable。
     * 注意：不加事务注解，避免只读事务传播到回放执行器内部的写操作。
     */
    public Map<String, Object> replayPayload(long auditId) {
        var entity = retrievalAuditRepository.findById(auditId)
                .orElseThrow(() -> BusinessException.notFound("召回审计记录不存在。"));
        var executor = replayExecutorProvider.getIfAvailable();
        if (executor == null) {
            return error("replay_unavailable", "检索回放执行器未接入，等待 search 模块集成。");
        }

        var requestPayload = new LinkedHashMap<>(entity.getRequestPayload() == null
                ? Map.<String, Object>of() : entity.getRequestPayload());
        requestPayload.put("retrievalAuditEnabled", false);
        var endpoint = str(entity.getEndpoint());

        long started = System.currentTimeMillis();
        Map<String, Object> replayResult = executor.replay(endpoint, entity.getUserId(), requestPayload);
        if (replayResult == null) {
            return error("unsupported_endpoint", "暂不支持回放 %s。".formatted(endpoint));
        }
        long durationMs = System.currentTimeMillis() - started;

        var replayHits = hitsFromResponse(replayResult);
        var originalIds = hitIds(entity.getHits());
        var replayIds = hitIds(replayHits);
        var overlap = new LinkedHashSet<>(originalIds);
        overlap.retainAll(replayIds);
        var newHitIds = replayIds.stream().filter(id -> !originalIds.contains(id)).limit(20).toList();
        var droppedHitIds = originalIds.stream().filter(id -> !replayIds.contains(id)).limit(20).toList();
        int replayTotal = totalHits(replayResult);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("auditId", auditId);
        body.put("endpoint", endpoint);
        body.put("durationMs", durationMs);
        body.put("originalTotalHits", entity.getTotalHits());
        body.put("replayTotalHits", replayTotal);
        body.put("overlapCount", overlap.size());
        body.put("newHitIds", newHitIds);
        body.put("droppedHitIds", droppedHitIds);
        body.put("hits", replayHits);
        body.put("toolTrace", replayResult.get("toolTrace") instanceof List<?> ? replayResult.get("toolTrace") : List.of());
        body.put("summary", "已用当前配置回放审计 #%d，返回 %d 条，Top 命中重合 %d 条。"
                .formatted(auditId, replayTotal, overlap.size()));
        return body;
    }

    private Map<String, Object> toMap(RetrievalAuditEntity entity, boolean includePayload) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", entity.getId());
        value.put("userId", entity.getUserId());
        value.put("endpoint", entity.getEndpoint());
        value.put("queryText", entity.getQueryText());
        value.put("city", entity.getCity());
        value.put("durationMs", entity.getDurationMs());
        value.put("totalHits", entity.getTotalHits());
        value.put("hits", entity.getHits());
        value.put("toolTrace", entity.getToolTrace());
        value.put("responseSummary", entity.getResponseSummary());
        value.put("createdAt", entity.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (includePayload) {
            value.put("requestPayload", entity.getRequestPayload());
        }
        return value;
    }

    private List<String> hitIds(List<Object> hits) {
        var ids = new ArrayList<String>();
        for (var item : hits == null ? List.of() : hits) {
            var id = str(asMap(item).get("id"));
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private String queryText(Map<String, Object> request, Map<String, Object> response) {
        return truncate(firstNonEmpty(str(request.get("text")), str(request.get("query")),
                str(response.get("queryText"))), 2000);
    }

    private String firstNonEmpty(String... values) {
        for (var value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private Map<String, Object> error(String code, String summary) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("code", code);
        body.put("summary", summary);
        return body;
    }
}

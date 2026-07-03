package com.renti.agent.modules.admin.application;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.renti.agent.common.exception.BusinessException;
import com.renti.agent.infrastructure.persistence.entity.AgentTraceEntity;
import com.renti.agent.infrastructure.persistence.repository.AgentTraceRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.renti.agent.modules.admin.application.ObservabilitySupport.asList;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.asMap;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.boundedInt;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.cleanText;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.str;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.stripBlockedKeys;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.totalPages;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.truncate;

/**
 * Agent 执行 trace：写入（供 agent 模块调用）与管理端查询。
 * 行为对齐旧 services/agent/agent_trace.py。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceService {

    private static final Set<String> BLOCKED_REQUEST_KEYS = Set.of(
            "password", "token", "sessiontoken", "session_token", "authorization",
            "api_key", "apikey", "deepseek_api_key", "amap_web_service_key");

    private final AgentTraceRepository agentTraceRepository;

    /**
     * 记录一次 agent 执行（对齐旧 record_agent_trace + build_agent_trace_values）。
     * 记录失败只打日志，不影响业务。
     *
     * @param resultPayload agent 最终响应（含 agent{status/mode/provider/model/usage}/parsed/toolTrace/summary 等）
     */
    @Transactional
    public void record(Long userId, String requestText, String city, String workspaceMode,
                       Map<String, Object> requestPayload, Map<String, Object> resultPayload,
                       long durationMs, String errorMessage) {
        var request = requestPayload == null ? Map.<String, Object>of() : requestPayload;
        if (Boolean.FALSE.equals(request.get("traceEnabled"))) {
            return;
        }
        try {
            var result = resultPayload == null ? Map.<String, Object>of() : resultPayload;
            var agent = asMap(result.get("agent"));
            var parsed = asMap(result.get("parsed"));
            var intent = agent.get("intent") instanceof Map<?, ?> ? asMap(agent.get("intent")) : intentFromParsed(parsed);
            var toolTrace = result.get("toolTrace") instanceof List<?> ? asList(result.get("toolTrace"))
                    : asList(agent.get("toolTrace"));
            var hasError = errorMessage != null && !errorMessage.isBlank();
            var status = !str(agent.get("status")).isEmpty() ? str(agent.get("status"))
                    : hasError ? "error"
                    : !str(result.get("intent")).isEmpty() ? str(result.get("intent")) : "unknown";

            var entity = new AgentTraceEntity();
            entity.setUserId(userId);
            entity.setRequestText(truncate(str(requestText), 2000));
            entity.setCity(truncate(city == null || city.isBlank() ? "上海" : city, 40));
            entity.setWorkspaceMode(truncate(workspaceMode == null || workspaceMode.isBlank()
                    ? "system_search" : workspaceMode, 40));
            entity.setAgentMode(truncate(str(agent.get("mode")), 40));
            entity.setStatus(truncate(status, 40));
            entity.setProvider(truncate(str(agent.get("provider")), 80));
            entity.setModel(truncate(str(agent.get("model")), 120));
            entity.setDurationMs((int) Math.max(0, durationMs));
            entity.setResultCount(ObservabilitySupport.totalHits(result));
            entity.setFallbackReason("fallback".equals(status) ? firstTraceSummary(toolTrace) : "");
            entity.setSummary(truncate(str(result.get("summary")), 2000));
            entity.setIntent(intent);
            entity.setToolTrace(toolTrace);
            entity.setUsage(asMap(agent.get("usage")));
            entity.setRequestPayload(stripBlockedKeys(request, BLOCKED_REQUEST_KEYS));
            entity.setErrorMessage(truncate(errorMessage == null ? "" : errorMessage, 1000));
            agentTraceRepository.save(entity);
        } catch (Exception exception) {
            log.debug("Agent trace recording failed: {}", exception.getMessage());
        }
    }

    /** GET /api/admin/agent-traces 响应 */
    @Transactional(readOnly = true)
    public Map<String, Object> listPayload(Integer limit, Integer page, Long userId, String status, String mode) {
        int pageSize = boundedInt(limit, 50, 1, 200);
        int pageNumber = boundedInt(page, 1, 1, 100000);
        var cleanStatus = cleanText(status, 40);
        var cleanMode = cleanText(mode, 40);

        Specification<AgentTraceEntity> spec = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (!cleanStatus.isEmpty()) {
                predicates.add(cb.equal(root.get("status"), cleanStatus));
            }
            if (!cleanMode.isEmpty()) {
                predicates.add(cb.equal(root.get("agentMode"), cleanMode));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(pageNumber - 1, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var result = agentTraceRepository.findAll(spec, pageable);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("traces", result.getContent().stream().map(entity -> toMap(entity, false)).toList());
        body.put("total", result.getTotalElements());
        body.put("page", pageNumber);
        body.put("pageSize", pageSize);
        body.put("totalPages", totalPages(result.getTotalElements(), pageSize));
        return body;
    }

    /** GET /api/admin/agent-traces/{id} 响应，缺失时 404 */
    @Transactional(readOnly = true)
    public Map<String, Object> detailPayload(long traceId) {
        var entity = agentTraceRepository.findById(traceId)
                .orElseThrow(() -> BusinessException.notFound("Agent trace 不存在。"));
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("trace", toMap(entity, true));
        return body;
    }

    private Map<String, Object> toMap(AgentTraceEntity entity, boolean includePayload) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", entity.getId());
        value.put("userId", entity.getUserId());
        value.put("requestText", entity.getRequestText());
        value.put("city", entity.getCity());
        value.put("workspaceMode", entity.getWorkspaceMode());
        value.put("agentMode", entity.getAgentMode());
        value.put("status", entity.getStatus());
        value.put("provider", entity.getProvider());
        value.put("model", entity.getModel());
        value.put("durationMs", entity.getDurationMs());
        value.put("resultCount", entity.getResultCount());
        value.put("fallbackReason", entity.getFallbackReason());
        value.put("summary", entity.getSummary());
        value.put("intent", entity.getIntent());
        value.put("toolTrace", entity.getToolTrace());
        value.put("usage", entity.getUsage());
        value.put("errorMessage", entity.getErrorMessage());
        value.put("createdAt", entity.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (includePayload) {
            value.put("requestPayload", entity.getRequestPayload());
        }
        return value;
    }

    /** 对齐旧 _intent_from_parsed */
    private Map<String, Object> intentFromParsed(Map<String, Object> parsed) {
        var constraints = asMap(parsed.get("constraints"));
        var intent = new LinkedHashMap<String, Object>();
        intent.put("city", parsed.get("city"));
        intent.put("locationKeyword", parsed.get("locationText"));
        intent.put("budgetMax", constraints.get("budgetMax"));
        intent.put("areaMin", constraints.get("areaMin"));
        intent.put("areaMax", constraints.get("areaMax"));
        intent.put("layout", constraints.get("layout"));
        intent.put("floorPreference", constraints.get("floorPreference"));
        intent.put("preferences", constraints.get("preferences") == null ? List.of() : constraints.get("preferences"));
        intent.put("environmentPreferences", constraints.get("environmentPreferences") == null
                ? List.of() : constraints.get("environmentPreferences"));
        intent.put("sort", parsed.get("sort"));
        intent.put("queryType", parsed.get("queryType"));
        intent.put("confidence", constraints.get("agentConfidence"));
        return intent;
    }

    private String firstTraceSummary(List<Object> toolTrace) {
        if (toolTrace == null || toolTrace.isEmpty()) {
            return "";
        }
        var first = asMap(toolTrace.getFirst());
        return truncate(str(first.get("summary")), 1000);
    }
}

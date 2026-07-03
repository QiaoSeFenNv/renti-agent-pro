package com.renti.agent.modules.admin.application;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.common.exception.BusinessException;
import com.renti.agent.infrastructure.persistence.entity.UserInteractionEntity;
import com.renti.agent.infrastructure.persistence.repository.UserInteractionRepository;
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
import static com.renti.agent.modules.admin.application.ObservabilitySupport.sanitizeMap;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.str;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.totalPages;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.truncate;

/**
 * 用户交互记录：写入（供各业务端点调用）与管理端查询。
 * 行为对齐旧 services/user_interactions.py（响应做摘要截断与脱敏）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserInteractionService {

    private final UserInteractionRepository userInteractionRepository;
    private final ObjectMapper objectMapper;

    /** 记录一次交互（对齐旧 record_user_interaction）；失败只打日志 */
    @Transactional
    public void record(Long userId, String endpoint, Map<String, Object> requestPayload,
                       Map<String, Object> responsePayload, long durationMs) {
        record(userId, endpoint, requestPayload, responsePayload, durationMs, null);
    }

    @Transactional
    public void record(Long userId, String endpoint, Map<String, Object> requestPayload,
                       Map<String, Object> responsePayload, long durationMs, String errorMessage) {
        var request = requestPayload == null ? Map.<String, Object>of() : requestPayload;
        if (Boolean.FALSE.equals(request.get("interactionLogEnabled"))) {
            return;
        }
        try {
            var response = responsePayload == null ? Map.<String, Object>of() : responsePayload;
            var parsed = asMap(response.get("parsed"));
            var agent = asMap(response.get("agent"));
            var intent = agent.get("intent") instanceof Map<?, ?> ? asMap(agent.get("intent"))
                    : intentFromResponse(response);
            var hasError = errorMessage != null && !errorMessage.isBlank();

            var entity = new UserInteractionEntity();
            entity.setUserId(userId);
            entity.setEndpoint(truncate(str(endpoint), 120));
            entity.setRequestText(requestText(request));
            entity.setCity(truncate(firstNonEmpty(str(request.get("city")), str(parsed.get("city")),
                    str(intent.get("city")), "上海"), 40));
            entity.setStatus(truncate(firstNonEmpty(str(agent.get("status")), str(response.get("intent")),
                    hasError ? "error" : "ok"), 40));
            entity.setDurationMs((int) Math.max(0, durationMs));
            entity.setResultCount(resultCount(response));
            entity.setSummary(truncate(str(response.get("summary")), 3000));
            entity.setIntent(intent);
            entity.setToolTrace(response.get("toolTrace") instanceof List<?> ? asList(response.get("toolTrace")) : List.of());
            entity.setRequestPayload(sanitizeMap(request));
            entity.setResponsePayload(sanitizeMap(response));
            entity.setErrorMessage(truncate(errorMessage == null ? "" : errorMessage, 1000));
            userInteractionRepository.save(entity);
        } catch (Exception exception) {
            log.debug("User interaction recording failed: {}", exception.getMessage());
        }
    }

    /** GET /api/admin/user-interactions 响应 */
    @Transactional(readOnly = true)
    public Map<String, Object> listPayload(Integer limit, Integer page, Long userId, String endpoint, String query) {
        int pageSize = boundedInt(limit, 50, 1, 200);
        int pageNumber = boundedInt(page, 1, 1, 100000);
        var cleanEndpoint = cleanText(endpoint, 120);
        var cleanQuery = cleanText(query, 120).toLowerCase();

        Specification<UserInteractionEntity> spec = (root, criteriaQuery, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (!cleanEndpoint.isEmpty()) {
                predicates.add(cb.equal(root.get("endpoint"), cleanEndpoint));
            }
            if (!cleanQuery.isEmpty()) {
                var like = "%" + cleanQuery + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("requestText")), like),
                        cb.like(cb.lower(root.get("summary")), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(pageNumber - 1, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var result = userInteractionRepository.findAll(spec, pageable);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("interactions", result.getContent().stream().map(entity -> toMap(entity, false)).toList());
        body.put("total", result.getTotalElements());
        body.put("page", pageNumber);
        body.put("pageSize", pageSize);
        body.put("totalPages", totalPages(result.getTotalElements(), pageSize));
        return body;
    }

    /** GET /api/admin/user-interactions/{id} 响应，缺失时 404 */
    @Transactional(readOnly = true)
    public Map<String, Object> detailPayload(long interactionId) {
        var entity = userInteractionRepository.findById(interactionId)
                .orElseThrow(() -> BusinessException.notFound("用户交互记录不存在。"));
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("interaction", toMap(entity, true));
        return body;
    }

    private Map<String, Object> toMap(UserInteractionEntity entity, boolean includePayload) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", entity.getId());
        value.put("userId", entity.getUserId());
        value.put("endpoint", entity.getEndpoint());
        value.put("requestText", entity.getRequestText());
        value.put("city", entity.getCity());
        value.put("status", entity.getStatus());
        value.put("durationMs", entity.getDurationMs());
        value.put("resultCount", entity.getResultCount());
        value.put("summary", entity.getSummary());
        value.put("intent", entity.getIntent());
        value.put("toolTrace", entity.getToolTrace());
        value.put("errorMessage", entity.getErrorMessage());
        value.put("createdAt", entity.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (includePayload) {
            value.put("requestPayload", entity.getRequestPayload());
            value.put("responsePayload", entity.getResponsePayload());
        }
        return value;
    }

    /** 对齐旧 _request_text：优先 text/query/message/question，否则脱敏后整体序列化 */
    private String requestText(Map<String, Object> payload) {
        for (var key : List.of("text", "query", "message", "question")) {
            var value = str(payload.get(key)).strip();
            if (!value.isEmpty()) {
                return truncate(value, 3000);
            }
        }
        try {
            return truncate(objectMapper.writeValueAsString(sanitizeMap(payload)), 3000);
        } catch (Exception exception) {
            return "";
        }
    }

    /** 对齐旧 _intent_from_response */
    private Map<String, Object> intentFromResponse(Map<String, Object> response) {
        var parsed = asMap(response.get("parsed"));
        var constraints = asMap(parsed.get("constraints"));
        var intent = new LinkedHashMap<String, Object>();
        intent.put("city", parsed.get("city"));
        intent.put("locationKeyword", parsed.get("locationText"));
        intent.put("budgetMax", constraints.get("budgetMax"));
        intent.put("layout", constraints.get("layout"));
        intent.put("preferences", constraints.get("preferences") == null ? List.of() : constraints.get("preferences"));
        intent.put("sort", parsed.get("sort"));
        intent.put("queryType", parsed.get("queryType"));
        return intent;
    }

    /** 对齐旧 _result_count：total 优先，否则取 recommendations/matches/rows/messages 长度 */
    private int resultCount(Map<String, Object> response) {
        if (response.get("total") != null) {
            var parsed = ObservabilitySupport.optionalInt(response.get("total"));
            return parsed == null ? 0 : parsed;
        }
        for (var key : List.of("recommendations", "matches", "rows", "messages")) {
            if (response.get(key) instanceof List<?> list) {
                return list.size();
            }
        }
        return 0;
    }

    private String firstNonEmpty(String... values) {
        for (var value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }
}

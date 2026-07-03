package com.renti.agent.modules.admin.application;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.infrastructure.persistence.entity.SystemLogEntity;
import com.renti.agent.infrastructure.persistence.repository.SystemLogRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.renti.agent.modules.admin.application.ObservabilitySupport.boundedInt;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.str;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.totalPages;

/**
 * 系统日志查询（system_logs 表，kind=api/llm/app）。
 * 响应结构对齐旧 services/system_logs.py admin_system_logs_payload。
 */
@Service
@RequiredArgsConstructor
public class SystemLogQueryService {

    private static final Set<String> LOG_KINDS = Set.of("api", "llm", "app");

    private final SystemLogRepository systemLogRepository;
    private final ObjectMapper objectMapper;

    /** GET /api/admin/logs?kind=&query=&level=&limit=&page= */
    @Transactional(readOnly = true)
    public Map<String, Object> logsPayload(String kind, String query, String level, Integer limit, Integer page) {
        var selectedKind = LOG_KINDS.contains(str(kind)) ? str(kind) : "api";
        int pageSize = boundedInt(limit, 100, 1, 500);
        int pageNumber = boundedInt(page, 1, 1, 100000);
        var cleanQuery = str(query).strip().toLowerCase();
        var storedLevel = storedLevel(level);

        Specification<SystemLogEntity> spec = (root, criteriaQuery, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("kind"), selectedKind));
            if (!storedLevel.isEmpty()) {
                predicates.add(cb.equal(root.get("level"), storedLevel));
            }
            if (!cleanQuery.isEmpty()) {
                predicates.add(cb.like(cb.lower(root.get("message")), "%" + cleanQuery + "%"));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(pageNumber - 1, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var result = systemLogRepository.findAll(spec, pageable);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("kind", selectedKind);
        body.put("file", "database://system_logs");
        body.put("logs", result.getContent().stream().map(this::logMap).toList());
        body.put("total", result.getTotalElements());
        body.put("page", pageNumber);
        body.put("pageSize", pageSize);
        body.put("totalPages", totalPages(result.getTotalElements(), pageSize));
        return body;
    }

    /** 对齐旧 _read_log_entries 的单条结构 */
    private Map<String, Object> logMap(SystemLogEntity entity) {
        var payload = entity.getPayload() == null ? Map.<String, Object>of() : entity.getPayload();
        var timestamp = entity.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        var displayLevel = displayLevel(entity.getLevel());

        var data = new LinkedHashMap<String, Object>(payload);
        data.putIfAbsent("timestamp", timestamp);
        data.put("level", displayLevel);
        data.putIfAbsent("message", entity.getMessage());

        var value = new LinkedHashMap<String, Object>();
        value.put("id", entity.getId());
        value.put("timestamp", timestamp);
        value.put("level", displayLevel);
        value.put("event", firstNonEmpty(str(payload.get("event")), entity.getMessage()));
        value.put("logger", "renti." + entity.getKind());
        value.put("message", entity.getMessage());
        value.put("data", data);
        value.put("line", printLine(entity.getKind(), data, entity.getMessage()));
        value.put("raw", rawJson(data));
        return value;
    }

    /** 对齐旧 _format_print_line 的紧凑单行展示 */
    private String printLine(String kind, Map<String, Object> event, String fallback) {
        var timestamp = str(event.get("timestamp")).replace("T", " ").replace("+00:00", "Z");
        var level = str(event.get("level")).isEmpty() ? "INFO" : str(event.get("level"));
        var eventName = firstNonEmpty(str(event.get("event")), str(event.get("message")), kind);
        var prefix = "%s [%s] %s".formatted(timestamp, level, eventName).strip();

        List<String> parts;
        if ("api.request".equals(eventName)) {
            parts = List.of(
                    joinPair(event.get("method"), event.get("path"), " "),
                    keyValue("status", event.get("statusCode")),
                    keyValue("duration", durationValue(event.get("durationMs"))),
                    keyValue("client", event.get("client")),
                    keyValue("error", event.get("error")));
        } else if ("llm".equals(kind) || eventName.startsWith("llm.")) {
            parts = List.of(
                    joinPair(event.get("provider"), event.get("model"), "/"),
                    keyValue("status", event.get("status")),
                    keyValue("duration", durationValue(event.get("durationMs"))),
                    keyValue("preview", event.get("requestPreview")),
                    keyValue("error", firstNonEmpty(str(event.get("summary")), str(event.get("error")))));
        } else {
            parts = List.of(
                    keyValue("status", event.get("status")),
                    keyValue("message", firstNonEmpty(str(event.get("message")), str(event.get("summary")))),
                    keyValue("duration", durationValue(event.get("durationMs"))));
        }
        var segments = new ArrayList<String>();
        segments.add(prefix);
        parts.stream().filter(part -> !part.isEmpty()).forEach(segments::add);
        var line = String.join(" | ", segments);
        line = line.isEmpty() ? str(fallback) : line;
        return line.length() > 2400 ? line.substring(0, 2400) : line;
    }

    private String rawJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception exception) {
            return String.valueOf(data);
        }
    }

    /** 请求参数（INFO/WARNING/ERROR 或小写）→ 存储值（info/warn/error） */
    private String storedLevel(String level) {
        return switch (str(level).strip().toLowerCase()) {
            case "info" -> "info";
            case "warn", "warning" -> "warn";
            case "error" -> "error";
            default -> "";
        };
    }

    /** 存储值 → 旧版展示值 */
    private String displayLevel(String level) {
        return switch (str(level).toLowerCase()) {
            case "warn", "warning" -> "WARNING";
            case "error" -> "ERROR";
            default -> "INFO";
        };
    }

    private String joinPair(Object left, Object right, String separator) {
        var leftText = str(left).strip();
        var rightText = str(right).strip();
        if (!leftText.isEmpty() && !rightText.isEmpty()) {
            return leftText + separator + rightText;
        }
        return leftText.isEmpty() ? rightText : leftText;
    }

    private String keyValue(String key, Object value) {
        var text = str(value).strip();
        return text.isEmpty() ? "" : key + "=" + text;
    }

    private String durationValue(Object value) {
        var text = str(value).strip();
        return text.isEmpty() ? "" : text + "ms";
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

package com.renti.agent.modules.graph.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.renti.agent.infrastructure.client.Neo4jHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.boundedInt;
import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.text;

/**
 * Neo4j 只读查询：状态检查、只读 Cypher 执行、房源关系上下文。
 * 行为对齐旧 graph/neo4j_store.py（只读校验）与 listing_graph.py
 * （neo4j_status_payload / neo4j_query_payload / listing_graph_context_payload）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphQueryService {

    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "CREATE", "MERGE", "SET", "DELETE", "DETACH", "REMOVE", "DROP", "LOAD", "IMPORT",
            "CALL DBMS", "CREATE CONSTRAINT", "CREATE INDEX");

    static final String LISTING_GRAPH_CONTEXT_CYPHER = """
            MATCH (seed:Listing)
            WHERE seed.id IN $listingIds
            OPTIONAL MATCH (seed)-[rel]->(node)
            WITH seed, collect(DISTINCT {
              type: type(rel),
              label: head(labels(node)),
              name: coalesce(node.name, node.id, node.title, '')
            })[..20] AS neighbors
            OPTIONAL MATCH (seed)-[:IN_COMMUNITY|IN_BUSINESS_AREA|NEAR_METRO|HAS_TAG]->(shared)<-[:IN_COMMUNITY|IN_BUSINESS_AREA|NEAR_METRO|HAS_TAG]-(related:Listing)
            WHERE related.id <> seed.id
            RETURN seed.id AS listingId,
                   neighbors AS neighbors,
                   collect(DISTINCT {
                     listingId: related.id,
                     title: related.title,
                     rentPrice: related.rentPrice,
                     sharedLabel: head(labels(shared)),
                     sharedName: coalesce(shared.name, '')
                   })[..10] AS relatedListings
            """;

    private final Neo4jHttpClient neo4jHttpClient;
    private final GraphConfigService graphConfigService;

    /** GET /api/admin/graph/neo4j/status */
    public Map<String, Object> statusPayload() {
        var neo4j = graphConfigService.effectiveNeo4j();
        var status = new LinkedHashMap<String, Object>();
        status.put("ok", false);
        status.put("configured", graphConfigService.configured(neo4j));
        status.put("urlConfigured", !text(neo4j.get("url"), "").isEmpty());
        status.put("apiKeyConfigured", !text(neo4j.get("apiKey"), "").isEmpty());
        status.put("username", text(neo4j.get("username"), "neo4j"));
        status.put("database", text(neo4j.get("database"), "neo4j"));
        status.put("auraConfigured", !text(neo4j.get("auraInstanceId"), "").isEmpty()
                || !text(neo4j.get("auraInstanceName"), "").isEmpty());
        status.put("transport", text(neo4j.get("transport"), "auto"));
        status.put("caBundleConfigured", !text(neo4j.get("caBundle"), "").isEmpty());
        status.put("nodeCount", 0);
        status.put("relationshipCount", 0);
        status.put("labels", List.of());
        status.put("relationshipTypes", List.of());
        status.put("diagnostic", "");
        status.put("warnings", List.of());
        status.put("summary", "");
        if (!graphConfigService.configured(neo4j)) {
            status.put("summary", "Neo4j URL 或 API key 未配置。");
            return status;
        }
        try {
            status.put("nodeCount", scalar("MATCH (n) RETURN count(n) AS value"));
            status.put("relationshipCount", scalar("MATCH ()-[r]->() RETURN count(r) AS value"));
            status.put("labels", values("CALL db.labels() YIELD label RETURN label AS value ORDER BY label LIMIT 50"));
            status.put("relationshipTypes", values(
                    "CALL db.relationshipTypes() YIELD relationshipType "
                            + "RETURN relationshipType AS value ORDER BY relationshipType LIMIT 50"));
            status.put("ok", true);
            status.put("summary", "Neo4j 已连通。");
            return status;
        } catch (Exception exception) {
            var diagnostic = diagnostic(exception);
            log.warn("Neo4j status check failed: {}", diagnostic);
            status.put("diagnostic", diagnostic);
            status.put("summary", "Neo4j 连通失败：" + exception.getClass().getSimpleName()
                    + (diagnostic.isEmpty() ? "" : "：" + diagnostic));
            return status;
        }
    }

    /** POST /api/admin/graph/neo4j/query {query, params, limit}：只读 Cypher */
    public Map<String, Object> queryPayload(Map<String, Object> payload) {
        var neo4j = graphConfigService.effectiveNeo4j();
        if (!graphConfigService.configured(neo4j)) {
            return error("not_configured", "Neo4j URL 或 API key 未配置，无法执行查询。");
        }
        var source = payload == null ? Map.<String, Object>of() : payload;
        var query = cleanQuery(String.valueOf(source.get("query") == null ? "" : source.get("query")));
        if (!isReadonlyCypher(query)) {
            return error("invalid_query", "只允许执行只读 Cypher 查询。");
        }
        var params = source.get("params") instanceof Map<?, ?> map ? asMap(map) : Map.<String, Object>of();
        int limit = boundedInt(source.get("limit"), 50, 1, 200);
        try {
            var rows = neo4jHttpClient.execute(query, params);
            var limited = rows.subList(0, Math.min(rows.size(), limit));
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("rows", limited);
            body.put("total", limited.size());
            body.put("summary", "Neo4j 查询返回 %d 行。".formatted(limited.size()));
            return body;
        } catch (Exception exception) {
            log.warn("Neo4j query failed: {}", exception.getMessage());
            return error("query_failed", "Neo4j 查询失败：" + exception.getClass().getSimpleName());
        }
    }

    /**
     * 相似房源关系上下文（给 rag/search/agent 模块用），对齐旧 listing_graph_context_payload。
     *
     * @return {ok, items[{listingId, neighbors, relatedListings}], summary} 或 {ok:false, code, summary}
     */
    public Map<String, Object> related(List<String> listingIds) {
        var cleanIds = new ArrayList<String>();
        for (var id : listingIds == null ? List.<String>of() : listingIds) {
            var clean = id == null ? "" : id.strip();
            if (!clean.isEmpty()) {
                cleanIds.add(clean);
            }
        }
        if (cleanIds.isEmpty()) {
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("items", List.of());
            body.put("summary", "没有需要查询关系图谱的房源。");
            return body;
        }
        var neo4j = graphConfigService.effectiveNeo4j();
        if (!graphConfigService.configured(neo4j)) {
            return error("not_configured", "Neo4j 未配置，已跳过关系图谱增强。");
        }
        try {
            var rows = neo4jHttpClient.execute(cleanQuery(LISTING_GRAPH_CONTEXT_CYPHER),
                    Map.of("listingIds", cleanIds.subList(0, Math.min(cleanIds.size(), 50))));
            var items = rows.stream().limit(50).map(this::normalizeContextRow).toList();
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("items", items);
            body.put("summary", "Neo4j 已返回 %d 条房源关系上下文。".formatted(items.size()));
            return body;
        } catch (Exception exception) {
            log.debug("Neo4j graph context failed: {}", exception.getMessage());
            return error("graph_context_failed", "Neo4j 关系增强失败：" + exception.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ 只读校验（对齐旧 neo4j_store.py）

    public static String cleanQuery(String query) {
        return String.join(" ", (query == null ? "" : query).strip().split("\\s+"));
    }

    public static boolean isReadonlyCypher(String query) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        var upper = query.toUpperCase();
        var allowedStart = upper.startsWith("MATCH ") || upper.startsWith("RETURN ")
                || upper.startsWith("CALL DB.") || upper.startsWith("SHOW ")
                || upper.startsWith("EXPLAIN ") || upper.startsWith("PROFILE ");
        if (!allowedStart) {
            return false;
        }
        return WRITE_KEYWORDS.stream().noneMatch(upper::contains);
    }

    // ------------------------------------------------------------------ helpers

    private int scalar(String query) {
        var rows = neo4jHttpClient.execute(query, Map.of());
        if (rows.isEmpty()) {
            return 0;
        }
        var value = rows.getFirst().get("value");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private List<String> values(String query) {
        var result = new ArrayList<String>();
        for (var row : neo4jHttpClient.execute(query, Map.of())) {
            var value = row.get("value");
            if (value != null && !String.valueOf(value).isEmpty()) {
                result.add(String.valueOf(value));
            }
        }
        return result;
    }

    /** 对齐旧 _normalize_graph_context_row */
    private Map<String, Object> normalizeContextRow(Map<String, Object> row) {
        var neighbors = new ArrayList<Map<String, Object>>();
        if (row.get("neighbors") instanceof List<?> rawNeighbors) {
            for (var item : rawNeighbors) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                var neighbor = asMap(item);
                if (text(neighbor.get("type"), "").isEmpty() && text(neighbor.get("name"), "").isEmpty()) {
                    continue;
                }
                var value = new LinkedHashMap<String, Object>();
                value.put("type", text(neighbor.get("type"), ""));
                value.put("label", text(neighbor.get("label"), ""));
                value.put("name", text(neighbor.get("name"), ""));
                neighbors.add(value);
                if (neighbors.size() >= 20) {
                    break;
                }
            }
        }
        var related = new ArrayList<Map<String, Object>>();
        if (row.get("relatedListings") instanceof List<?> rawRelated) {
            for (var item : rawRelated) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                var entry = asMap(item);
                if (text(entry.get("listingId"), "").isEmpty()) {
                    continue;
                }
                var value = new LinkedHashMap<String, Object>();
                value.put("listingId", text(entry.get("listingId"), ""));
                value.put("title", text(entry.get("title"), ""));
                value.put("rentPrice", entry.get("rentPrice"));
                value.put("sharedLabel", text(entry.get("sharedLabel"), ""));
                value.put("sharedName", text(entry.get("sharedName"), ""));
                related.add(value);
                if (related.size() >= 10) {
                    break;
                }
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("listingId", text(row.get("listingId"), ""));
        result.put("neighbors", neighbors);
        result.put("relatedListings", related);
        return result;
    }

    private String diagnostic(Exception exception) {
        var detail = exception.getMessage() == null ? "" : exception.getMessage().strip();
        return detail.length() > 400 ? detail.substring(0, 397) + "..." : detail;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private static Map<String, Object> error(String code, String summary) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("code", code);
        body.put("summary", summary);
        return body;
    }
}

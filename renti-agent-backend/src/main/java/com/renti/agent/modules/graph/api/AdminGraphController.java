package com.renti.agent.modules.graph.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentAdmin;
import com.renti.agent.modules.auth.application.AdminPrincipal;
import com.renti.agent.modules.graph.application.GraphConfigService;
import com.renti.agent.modules.graph.application.GraphQueryService;
import com.renti.agent.modules.graph.application.GraphSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Neo4j 图谱管理端：配置、状态、只读查询、房源同步。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/graph/neo4j")
@RequiredArgsConstructor
public class AdminGraphController {

    private final GraphConfigService graphConfigService;
    private final GraphQueryService graphQueryService;
    private final GraphSyncService graphSyncService;

    @GetMapping("/config")
    public Map<String, Object> config(@CurrentAdmin AdminPrincipal admin) {
        return graphConfigService.getPayload();
    }

    @PutMapping("/config")
    public Map<String, Object> updateConfig(@CurrentAdmin AdminPrincipal admin,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        log.info("Admin {} updated Neo4j config", admin.username());
        return graphConfigService.updatePayload(payload);
    }

    @GetMapping("/status")
    public Map<String, Object> status(@CurrentAdmin AdminPrincipal admin) {
        return graphQueryService.statusPayload();
    }

    /** 只读 Cypher 查询 {query, params, limit} */
    @PostMapping("/query")
    public Map<String, Object> query(@CurrentAdmin AdminPrincipal admin,
                                     @RequestBody(required = false) Map<String, Object> payload) {
        return graphQueryService.queryPayload(payload);
    }

    /** 发布库 → 图谱同步 {city, query, limit} */
    @PostMapping("/sync-listings")
    public Map<String, Object> syncListings(@CurrentAdmin AdminPrincipal admin,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var city = String.valueOf(source.get("city") == null ? "" : source.get("city"));
        var query = String.valueOf(source.get("query") == null ? "" : source.get("query"));
        var limit = source.get("limit") instanceof Number number ? number.intValue() : null;
        log.info("Admin {} syncing listings into Neo4j, city={}, limit={}", admin.username(), city, limit);
        return graphSyncService.syncListings(city, query, limit);
    }
}

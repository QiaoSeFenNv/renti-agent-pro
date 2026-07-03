package com.renti.agent.modules.rag.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentAdmin;
import com.renti.agent.modules.auth.application.AdminPrincipal;
import com.renti.agent.modules.rag.application.ListingIndexService;
import com.renti.agent.modules.rag.application.RagConfigService;
import com.renti.agent.modules.rag.application.VectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 管理端：配置、Qdrant 状态/浏览/检索/索引。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/rag")
@RequiredArgsConstructor
public class AdminRagController {

    private final RagConfigService ragConfigService;
    private final ListingIndexService listingIndexService;
    private final VectorSearchService vectorSearchService;

    @GetMapping("/config")
    public Map<String, Object> config(@CurrentAdmin AdminPrincipal admin) {
        return ragConfigService.getPayload();
    }

    @PutMapping("/config")
    public Map<String, Object> updateConfig(@CurrentAdmin AdminPrincipal admin,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        log.info("Admin {} updated RAG config", admin.username());
        return ragConfigService.updatePayload(payload);
    }

    @GetMapping("/qdrant/status")
    public Map<String, Object> qdrantStatus(@CurrentAdmin AdminPrincipal admin) {
        return listingIndexService.statusPayload();
    }

    @GetMapping("/qdrant/points")
    public Map<String, Object> qdrantPoints(@CurrentAdmin AdminPrincipal admin,
                                            @RequestParam(defaultValue = "") String city,
                                            @RequestParam(defaultValue = "active") String status,
                                            @RequestParam(required = false) Integer limit,
                                            @RequestParam(required = false) String offset) {
        return listingIndexService.pointsPayload(city, status, limit, offset);
    }

    /** 语义搜索测试 {text, city, limit} */
    @PostMapping("/qdrant/search")
    public Map<String, Object> qdrantSearch(@CurrentAdmin AdminPrincipal admin,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var text = String.valueOf(source.get("text") == null ? "" : source.get("text"));
        var city = String.valueOf(source.get("city") == null ? "" : source.get("city"));
        var limit = source.get("limit") instanceof Number number ? number.intValue() : null;
        return vectorSearchService.searchPayload(text, city, limit);
    }

    /** 发布库 → 向量索引 {city, query, limit} */
    @PostMapping("/qdrant/index-listings")
    public Map<String, Object> indexListings(@CurrentAdmin AdminPrincipal admin,
                                             @RequestBody(required = false) Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var city = String.valueOf(source.get("city") == null ? "" : source.get("city"));
        var query = String.valueOf(source.get("query") == null ? "" : source.get("query"));
        var limit = source.get("limit") instanceof Number number ? number.intValue() : null;
        log.info("Admin {} indexing listings into Qdrant, city={}, limit={}", admin.username(), city, limit);
        return listingIndexService.indexListings(city, query, limit);
    }
}

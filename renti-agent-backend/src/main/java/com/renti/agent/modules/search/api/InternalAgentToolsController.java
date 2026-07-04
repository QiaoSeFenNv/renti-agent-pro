package com.renti.agent.modules.search.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.modules.graph.application.GraphQueryService;
import com.renti.agent.modules.listing.application.ListingPayloadMapper;
import com.renti.agent.modules.listing.application.ListingQueryService;
import com.renti.agent.modules.rag.application.VectorSearchService;
import com.renti.agent.modules.search.application.ListingSearchService;
import com.renti.agent.modules.search.application.PlaceService;
import com.renti.agent.modules.search.application.RequirementParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.renti.agent.modules.search.application.SearchSupport.asMap;
import static com.renti.agent.modules.search.application.SearchSupport.cleanText;
import static com.renti.agent.modules.search.application.SearchSupport.firstNonEmpty;
import static com.renti.agent.modules.search.application.SearchSupport.intOr;
import static com.renti.agent.modules.search.application.SearchSupport.str;
import static com.renti.agent.modules.search.application.SearchSupport.stringList;

/**
 * Python agent 回调工具端点（契约 §B，X-Internal-Token 由 InternalTokenInterceptor 校验）。
 * 响应形状以 agent-service/app/tools.py 的消费方式为准。
 */
@Slf4j
@RestController
@RequestMapping("/internal/agent-tools")
@RequiredArgsConstructor
public class InternalAgentToolsController {

    private final RequirementParseService requirementParseService;
    private final PlaceService placeService;
    private final ListingSearchService listingSearchService;
    private final VectorSearchService vectorSearchService;
    private final GraphQueryService graphQueryService;
    private final ListingQueryService listingQueryService;
    private final ListingPayloadMapper listingPayloadMapper;

    /** {text, city} → Requirement（扁平 camelCase） */
    @PostMapping("/parse-requirement")
    public Map<String, Object> parseRequirement(@RequestBody(required = false) Map<String, Object> payload) {
        var source = orEmpty(payload);
        var requirement = requirementParseService.parseRequirementText(str(source.get("text")), null);
        var city = cleanText(source.get("city"), 40);
        if (!city.isEmpty()) {
            requirement.put("city", city);
        }
        return requirement;
    }

    /** {locationText, city} → {ok, longitude, latitude, label, district, source} / {ok:false, code} */
    @PostMapping("/geocode")
    public Map<String, Object> geocode(@RequestBody(required = false) Map<String, Object> payload) {
        var source = orEmpty(payload);
        var result = placeService.resolveLocationGeocodePayload(Map.of(
                "city", cleanText(source.get("city"), 40),
                "address", cleanText(source.get("locationText"), 160)));
        var body = new LinkedHashMap<String, Object>();
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            body.put("ok", false);
            body.put("code", firstNonEmpty(str(result.get("code")), "geocode_failed"));
            body.put("summary", str(result.get("summary")));
            return body;
        }
        var location = asMap(result.get("location"));
        body.put("ok", true);
        body.put("longitude", location.get("longitude"));
        body.put("latitude", location.get("latitude"));
        body.put("label", location.get("label"));
        body.put("district", location.get("district"));
        body.put("source", location.get("source"));
        return body;
    }

    /** 主库条件检索 → {listings, total} */
    @PostMapping("/search-listings-sql")
    public Map<String, Object> searchListingsSql(@RequestBody(required = false) Map<String, Object> payload) {
        return listingSearchService.internalSqlSearchPayload(orEmpty(payload));
    }

    /** {text, city, limit} → {hits:[{listingId, score, ...}]} */
    @PostMapping("/search-listings-vector")
    public Map<String, Object> searchListingsVector(@RequestBody(required = false) Map<String, Object> payload) {
        var source = orEmpty(payload);
        var body = new LinkedHashMap<String, Object>();
        try {
            body.put("hits", vectorSearchService.search(
                    str(source.get("text")), cleanText(source.get("city"), 40),
                    Math.max(1, Math.min(intOr(source.get("limit"), 20), 50))));
        } catch (Exception exception) {
            log.warn("internal vector search failed: {}", exception.getMessage());
            body.put("hits", List.of());
            body.put("warning", exception.getMessage());
        }
        return body;
    }

    /** {listingIds, city} → {related:[{listingId, neighbors, relatedListings}]} */
    @PostMapping("/search-listings-graph")
    public Map<String, Object> searchListingsGraph(@RequestBody(required = false) Map<String, Object> payload) {
        var source = orEmpty(payload);
        var body = new LinkedHashMap<String, Object>();
        try {
            var result = graphQueryService.related(stringList(source.get("listingIds")));
            body.put("related", result.get("items") instanceof List<?> items ? items : List.of());
            body.put("summary", str(result.get("summary")));
        } catch (Exception exception) {
            log.warn("internal graph search failed: {}", exception.getMessage());
            body.put("related", List.of());
            body.put("warning", exception.getMessage());
        }
        return body;
    }

    /** {listingId} → {ok, listing} */
    @PostMapping("/listing-detail")
    public Map<String, Object> listingDetail(@RequestBody(required = false) Map<String, Object> payload) {
        var listingId = cleanText(orEmpty(payload).get("listingId"), 64);
        var body = new LinkedHashMap<String, Object>();
        var entity = listingQueryService.findActive(listingId).orElse(null);
        if (entity == null) {
            body.put("ok", false);
            body.put("code", "not_found");
            return body;
        }
        body.put("ok", true);
        body.put("listing", listingPayloadMapper.listingPayload(entity));
        return body;
    }

    private static Map<String, Object> orEmpty(Map<String, Object> payload) {
        return payload == null ? Map.of() : payload;
    }
}

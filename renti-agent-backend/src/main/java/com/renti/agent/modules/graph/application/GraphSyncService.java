package com.renti.agent.modules.graph.application;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.infrastructure.client.Neo4jHttpClient;
import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.modules.rag.application.ListingIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布房源 → Neo4j 图谱同步。节点/关系模型与 Cypher 照移植旧
 * graph/neo4j_store.py 的 _UPSERT_LISTINGS_CYPHER 与 listing_graph.py 的 _listing_graph_row。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphSyncService {

    static final String UPSERT_LISTINGS_CYPHER = """
            UNWIND $rows AS row
            MERGE (listing:Listing {id: row.id})
            SET listing.title = row.title,
                listing.city = row.city,
                listing.district = row.district,
                listing.businessArea = row.businessArea,
                listing.community = row.community,
                listing.rentPrice = row.rentPrice,
                listing.layout = row.layout,
                listing.areaSqm = row.areaSqm,
                listing.rentType = row.rentType,
                listing.longitude = row.longitude,
                listing.latitude = row.latitude,
                listing.nearestMetro = row.nearestMetro,
                listing.metroDistanceM = row.metroDistanceM,
                listing.source = row.source,
                listing.sourceUrl = row.sourceUrl,
                listing.provider = row.provider,
                listing.updatedAt = row.updatedAt
            MERGE (city:City {name: row.city})
            MERGE (listing)-[:IN_CITY]->(city)
            FOREACH (_ IN CASE WHEN row.district <> '' THEN [1] ELSE [] END |
              MERGE (district:District {name: row.district, city: row.city})
              MERGE (listing)-[:IN_DISTRICT]->(district)
            )
            FOREACH (_ IN CASE WHEN row.businessArea <> '' THEN [1] ELSE [] END |
              MERGE (area:BusinessArea {name: row.businessArea, city: row.city})
              MERGE (listing)-[:IN_BUSINESS_AREA]->(area)
            )
            FOREACH (_ IN CASE WHEN row.community <> '' THEN [1] ELSE [] END |
              MERGE (community:Community {name: row.community, city: row.city})
              MERGE (listing)-[:IN_COMMUNITY]->(community)
            )
            FOREACH (_ IN CASE WHEN row.nearestMetro <> '' THEN [1] ELSE [] END |
              MERGE (metro:MetroStation {name: row.nearestMetro, city: row.city})
              MERGE (listing)-[:NEAR_METRO]->(metro)
            )
            FOREACH (tagName IN row.tags |
              MERGE (tag:ListingTag {name: tagName})
              MERGE (listing)-[:HAS_TAG]->(tag)
            )
            FOREACH (riskName IN row.riskTags |
              MERGE (risk:RiskTag {name: riskName})
              MERGE (listing)-[:HAS_RISK]->(risk)
            )
            """;

    private final Neo4jHttpClient neo4jHttpClient;
    private final GraphConfigService graphConfigService;
    private final ListingIndexService listingIndexService;

    /** POST /api/admin/graph/neo4j/sync-listings {city, query, limit}，对齐旧 sync_published_listings_to_neo4j_payload */
    @Transactional(readOnly = true)
    public Map<String, Object> syncListings(String city, String query, Integer limit) {
        var neo4j = graphConfigService.effectiveNeo4j();
        if (!graphConfigService.configured(neo4j)) {
            return error("not_configured", "Neo4j URL 或 API key 未配置，无法同步房源图谱。");
        }
        int selectedLimit = Math.max(1, Math.min(limit == null ? 200 : limit, 1000));
        var listings = listingIndexService.loadPublishedListings(city, query, selectedLimit);
        if (listings.isEmpty()) {
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("synced", 0);
            body.put("summary", "没有需要同步到 Neo4j 的已发布房源。");
            return body;
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (var listing : listings) {
            rows.add(graphRow(listing));
        }
        try {
            neo4jHttpClient.execute(UPSERT_LISTINGS_CYPHER, Map.of("rows", rows));
            log.info("Synced {} listings into Neo4j graph", rows.size());
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("synced", rows.size());
            body.put("summary", "已同步 Neo4j 房源图谱 %d 套房源。".formatted(rows.size()));
            return body;
        } catch (Exception exception) {
            log.warn("Neo4j sync failed: {}", exception.getMessage());
            return error("sync_failed", "Neo4j 同步失败：" + exception.getClass().getSimpleName());
        }
    }

    /** 对齐旧 _listing_graph_row */
    Map<String, Object> graphRow(ListingEntity listing) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", nz(listing.getListingId()));
        row.put("title", nz(listing.getTitle()));
        row.put("city", nz(listing.getCity()));
        row.put("district", nz(listing.getDistrict()));
        row.put("businessArea", nz(listing.getBusinessArea()));
        row.put("community", nz(listing.getCommunity()));
        row.put("rentPrice", listing.getRentPrice());
        row.put("layout", nz(listing.getLayout()));
        row.put("areaSqm", listing.getAreaSqm() == null ? 0 : listing.getAreaSqm());
        row.put("rentType", nz(listing.getRentType()));
        row.put("longitude", listing.getLongitude());
        row.put("latitude", listing.getLatitude());
        row.put("nearestMetro", nz(listing.getNearestMetro()));
        row.put("metroDistanceM", listing.getMetroDistanceM() == null ? 0 : listing.getMetroDistanceM());
        row.put("source", nz(listing.getSource()));
        row.put("sourceUrl", nz(listing.getSourceUrl()));
        row.put("provider", !nz(listing.getProvider()).isEmpty() ? listing.getProvider() : nz(listing.getSource()));
        row.put("updatedAt", listing.getUpdatedAt() == null ? ""
                : listing.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        row.put("tags", cleanTags(listing.getTags()));
        row.put("riskTags", cleanTags(listing.getRiskTags()));
        return row;
    }

    private List<String> cleanTags(List<String> tags) {
        var result = new ArrayList<String>();
        for (var tag : tags == null ? List.<String>of() : tags) {
            if (tag != null && !tag.strip().isEmpty()) {
                result.add(tag);
            }
        }
        return result;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> error(String code, String summary) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("code", code);
        body.put("summary", summary);
        return body;
    }
}

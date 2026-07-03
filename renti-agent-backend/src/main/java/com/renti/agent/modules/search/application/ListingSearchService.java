package com.renti.agent.modules.search.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.modules.listing.application.ListingPayloadMapper;
import com.renti.agent.modules.listing.application.ListingQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.renti.agent.modules.search.application.SearchSupport.DEFAULT_CITY;
import static com.renti.agent.modules.search.application.SearchSupport.cleanText;
import static com.renti.agent.modules.search.application.SearchSupport.distanceMeters;
import static com.renti.agent.modules.search.application.SearchSupport.intOr;
import static com.renti.agent.modules.search.application.SearchSupport.optionalDouble;
import static com.renti.agent.modules.search.application.SearchSupport.optionalInt;
import static com.renti.agent.modules.search.application.SearchSupport.queryTokens;
import static com.renti.agent.modules.search.application.SearchSupport.str;

/**
 * 正式房源库 SQL 检索：城市 + 关键词 token 模糊匹配 + 条件过滤 + Haversine 距离。
 * 匹配语义对齐旧 listing_ingestion.load_published_listings / _published_filter_params
 * （token 对 listing_id/provider/source_name/source_url/listing_json 做 OR ILIKE）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingSearchService {

    private final ListingQueryService listingQueryService;
    private final ListingPayloadMapper listingPayloadMapper;
    private final ObjectMapper objectMapper;

    /** 城市在架房源（payload camelCase） */
    public List<Map<String, Object>> loadByCity(String city) {
        var selectedCity = cleanText(city, 40).isEmpty() ? DEFAULT_CITY : cleanText(city, 40);
        return listingQueryService.findActiveByCity(selectedCity).stream()
                .map(listingPayloadMapper::listingPayload)
                .toList();
    }

    /** 城市 + 关键词检索；query 为空时返回全部城市房源 */
    public List<Map<String, Object>> loadByQuery(String query, String city) {
        var selectedCity = cleanText(city, 40).isEmpty() ? DEFAULT_CITY : cleanText(city, 40);
        var tokens = queryTokens(query);
        var entities = listingQueryService.findActiveByCity(selectedCity);
        if (tokens.isEmpty()) {
            return entities.stream().map(listingPayloadMapper::listingPayload).toList();
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var entity : entities) {
            var haystack = searchableText(entity);
            for (var token : tokens) {
                if (haystack.contains(token.toLowerCase())) {
                    result.add(listingPayloadMapper.listingPayload(entity));
                    break;
                }
            }
        }
        return result;
    }

    /** 按业务 ID 加载在架房源（限定城市），供向量/图谱召回回表 */
    public Map<String, Object> loadById(String listingId, String city) {
        var selectedCity = cleanText(city, 40).isEmpty() ? DEFAULT_CITY : cleanText(city, 40);
        return listingQueryService.findActive(cleanText(listingId, 64))
                .filter(entity -> selectedCity.equals(entity.getCity()))
                .map(listingPayloadMapper::listingPayload)
                .orElse(null);
    }

    /**
     * POST /internal/agent-tools/search-listings-sql：
     * 城市 + 预算/户型/租赁方式过滤 + 可选中心/半径（Haversine 升序），返回 {listings, total}。
     */
    public Map<String, Object> internalSqlSearchPayload(Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var city = cleanText(source.get("city"), 40).isEmpty() ? DEFAULT_CITY : cleanText(source.get("city"), 40);
        var centerLongitude = optionalDouble(source.get("centerLongitude"));
        var centerLatitude = optionalDouble(source.get("centerLatitude"));
        var radiusMeters = optionalInt(source.get("radiusMeters"));
        var budgetMax = optionalInt(source.get("budgetMax"));
        var layout = cleanText(source.get("layout"), 40);
        var rentType = cleanText(source.get("rentType"), 16);
        int limit = Math.max(1, Math.min(intOr(source.get("limit"), 40), 100));
        boolean hasCenter = centerLongitude != null && centerLatitude != null;

        var rows = new ArrayList<Map<String, Object>>();
        for (var listing : loadByCity(city)) {
            if (budgetMax != null && intOr(listing.get("rentPrice"), 0) > budgetMax) {
                continue;
            }
            if (!layout.isEmpty() && !RecommendationService.layoutMatches(layout, str(listing.get("layout")))) {
                continue;
            }
            if (!rentType.isEmpty() && !rentType.equals(str(listing.get("rentType")))) {
                continue;
            }
            var row = new LinkedHashMap<>(listing);
            if (hasCenter) {
                var distance = (int) Math.round(distanceMeters(
                        centerLongitude, centerLatitude,
                        SearchSupport.doubleOr(listing.get("longitude"), 0),
                        SearchSupport.doubleOr(listing.get("latitude"), 0)));
                if (radiusMeters != null && radiusMeters > 0 && distance > radiusMeters) {
                    continue;
                }
                row.put("distanceM", distance);
            }
            rows.add(row);
        }
        if (hasCenter) {
            rows.sort((left, right) -> Integer.compare(
                    intOr(left.get("distanceM"), Integer.MAX_VALUE),
                    intOr(right.get("distanceM"), Integer.MAX_VALUE)));
        }
        var selected = rows.subList(0, Math.min(rows.size(), limit));
        var body = new LinkedHashMap<String, Object>();
        body.put("listings", selected);
        body.put("total", rows.size());
        return body;
    }

    /** 近似旧 listing_json ILIKE：拼接实体标识字段 + 序列化 payload，小写化 */
    private String searchableText(ListingEntity entity) {
        var builder = new StringBuilder();
        builder.append(str(entity.getListingId())).append(' ')
                .append(str(entity.getProvider())).append(' ')
                .append(str(entity.getSourceName())).append(' ')
                .append(str(entity.getSourceUrl())).append(' ');
        try {
            builder.append(objectMapper.writeValueAsString(listingPayloadMapper.listingPayload(entity)));
        } catch (Exception exception) {
            builder.append(str(entity.getTitle())).append(' ')
                    .append(str(entity.getCommunity())).append(' ')
                    .append(str(entity.getDistrict())).append(' ')
                    .append(str(entity.getBusinessArea()));
        }
        return builder.toString().toLowerCase();
    }
}

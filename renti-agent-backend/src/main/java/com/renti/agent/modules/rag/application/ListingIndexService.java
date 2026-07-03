package com.renti.agent.modules.rag.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.renti.agent.infrastructure.client.JinaEmbeddingClient;
import com.renti.agent.infrastructure.client.QdrantClient;
import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.infrastructure.persistence.repository.ListingRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.renti.agent.modules.platform.application.SystemIntegrationsConfigService.text;

/**
 * 房源向量索引：发布库 → Qdrant。文档模板、point 结构与响应对齐旧
 * rag/listing_indexer.py（listing_to_document / _listing_payload / _point_id 等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingIndexService {

    /** uuid5 的 NAMESPACE_URL（与 Python uuid.NAMESPACE_URL 一致） */
    private static final UUID NAMESPACE_URL = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private final ListingRepository listingRepository;
    private final JinaEmbeddingClient jinaEmbeddingClient;
    private final QdrantClient qdrantClient;
    private final RagConfigService ragConfigService;

    /** GET /api/admin/rag/qdrant/status */
    @Transactional(readOnly = true)
    public Map<String, Object> statusPayload() {
        var rag = ragConfigService.effectiveRag();
        var status = new LinkedHashMap<String, Object>();
        status.put("ok", false);
        status.put("configured", ragConfigService.indexingConfigured(rag));
        status.put("qdrantConfigured", ragConfigService.qdrantConfigured(rag));
        status.put("embeddingConfigured", ragConfigService.embeddingConfigured(rag));
        status.put("embeddingAvailable", ragConfigService.embeddingAvailable(rag));
        status.put("collection", text(rag.get("qdrantCollection"), qdrantClient.collectionName()));
        status.put("embeddingProvider", text(rag.get("embeddingProvider"), "auto"));
        status.put("effectiveEmbeddingProvider", ragConfigService.effectiveEmbeddingProvider(rag));
        status.put("collections", List.of());
        status.put("collectionExists", false);
        status.put("pointsCount", 0);
        status.put("indexedVectorsCount", 0);
        status.put("segmentsCount", 0);
        status.put("vectorSize", 0);
        status.put("distance", "");
        status.put("summary", "");
        if (!ragConfigService.qdrantConfigured(rag)) {
            status.put("summary", "Qdrant URL 或 API key 未配置。");
            return status;
        }
        try {
            var collections = qdrantClient.listCollections();
            var collectionExists = collections.contains(qdrantClient.collectionName());
            var info = collectionExists ? qdrantClient.collectionInfo() : Map.<String, Object>of();
            var vectorParams = vectorParams(info);
            status.put("ok", true);
            status.put("collections", collections);
            status.put("collectionExists", collectionExists);
            status.put("pointsCount", intOf(info.get("points_count")));
            status.put("indexedVectorsCount", intOf(info.get("indexed_vectors_count")));
            status.put("segmentsCount", intOf(info.get("segments_count")));
            status.put("vectorSize", intOf(vectorParams.get("size")));
            status.put("distance", text(vectorParams.get("distance"), ""));
            status.put("summary", "Qdrant 已连通，当前 collection 数量 %d。".formatted(collections.size()));
            return status;
        } catch (Exception exception) {
            log.warn("Qdrant status check failed: {}", exception.getMessage());
            status.put("summary", "Qdrant 连通失败：" + exception.getClass().getSimpleName());
            return status;
        }
    }

    /** POST /api/admin/rag/qdrant/index-listings {city, query, limit} */
    @Transactional(readOnly = true)
    public Map<String, Object> indexListings(String city, String query, Integer limit) {
        var rag = ragConfigService.effectiveRag();
        if (!ragConfigService.qdrantConfigured(rag)) {
            return error("not_configured", "Qdrant 或 embedding 配置不完整，无法建立语义索引。");
        }
        int selectedLimit = bounded(limit, 200, 1, 1000);
        var listings = loadPublishedListings(city, query, selectedLimit);
        if (listings.isEmpty()) {
            var empty = new LinkedHashMap<String, Object>();
            empty.put("ok", true);
            empty.put("indexed", 0);
            empty.put("collection", qdrantClient.collectionName());
            empty.put("summary", "没有需要索引的已发布房源。");
            return empty;
        }
        try {
            var points = new ArrayList<Map<String, Object>>();
            int vectorSize = 0;
            for (var listing : listings) {
                var document = listingToDocument(listing);
                var vector = jinaEmbeddingClient.embedDocument(document);
                vectorSize = vectorSize == 0 ? vector.size() : vectorSize;
                var point = new LinkedHashMap<String, Object>();
                point.put("id", pointId(listing.getListingId()));
                point.put("vector", vector);
                var payload = listingPayload(listing);
                payload.put("document", document);
                point.put("payload", payload);
                points.add(point);
            }
            qdrantClient.ensureCollection(vectorSize);
            int indexed = qdrantClient.upsertPoints(points);
            log.info("Indexed {} listings into Qdrant collection {}", indexed, qdrantClient.collectionName());
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("indexed", indexed);
            body.put("collection", qdrantClient.collectionName());
            body.put("summary", "已写入 Qdrant 语义索引 %d 套房源。".formatted(indexed));
            return body;
        } catch (Exception exception) {
            log.warn("Qdrant indexing failed: {}", exception.getMessage());
            return error("index_failed", "Qdrant 索引失败：" + exception.getClass().getSimpleName());
        }
    }

    /** GET /api/admin/rag/qdrant/points?city=&status=&limit=&offset= */
    @Transactional(readOnly = true)
    public Map<String, Object> pointsPayload(String city, String status, Integer limit, String offset) {
        var rag = ragConfigService.effectiveRag();
        if (!ragConfigService.qdrantConfigured(rag)) {
            return error("not_configured", "Qdrant URL 或 API key 未配置，无法查看向量数据。");
        }
        var filters = new LinkedHashMap<String, Object>();
        if (city != null && !city.isBlank()) {
            filters.put("city", city);
        }
        if (status != null && !status.isBlank()) {
            filters.put("status", status);
        }
        try {
            var result = qdrantClient.scrollPoints(bounded(limit, 20, 1, 100), filters,
                    offset == null || offset.isBlank() ? null : offset);
            var rows = result.get("points") instanceof List<?> list ? list : List.of();
            var points = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                if (row instanceof Map<?, ?>) {
                    points.add(pointFromRow(asMap(row)));
                }
            }
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", true);
            body.put("collection", qdrantClient.collectionName());
            body.put("points", points);
            body.put("nextPageOffset", result.get("next_page_offset"));
            body.put("summary", "已读取 Qdrant 向量点 %d 条。".formatted(points.size()));
            return body;
        } catch (Exception exception) {
            log.warn("Qdrant points scroll failed: {}", exception.getMessage());
            return error("points_failed", "Qdrant 向量点读取失败：" + exception.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ 文档与 point 结构

    /** 向量文本模板：照抄旧 listing_to_document */
    public String listingToDocument(ListingEntity listing) {
        var parts = List.of(
                nz(listing.getTitle()),
                nz(listing.getCommunity()),
                nz(listing.getCity()),
                nz(listing.getDistrict()),
                nz(listing.getBusinessArea()),
                nz(listing.getLayout()),
                nz(listing.getRentType()),
                "租金 %d 元".formatted(listing.getRentPrice()),
                "面积 %d 平方米".formatted(listing.getAreaSqm() == null ? 0 : listing.getAreaSqm()),
                "距 %s 地铁约 %d 米".formatted(nz(listing.getNearestMetro()),
                        listing.getMetroDistanceM() == null ? 0 : listing.getMetroDistanceM()),
                "标签：" + String.join("、", listing.getTags() == null ? List.of() : listing.getTags()),
                "风险：" + String.join("、", listing.getRiskTags() == null ? List.of() : listing.getRiskTags()),
                rawSummary(listing.getRaw()));
        var document = String.join("\n", parts.stream().filter(part -> !part.strip().isEmpty()).toList());
        return document.length() > 8000 ? document.substring(0, 8000) : document;
    }

    /** point id：uuid5(NAMESPACE_URL, "renti-listing:{id}")，与旧 _point_id 一致 */
    public static String pointId(String listingId) {
        return uuid5(NAMESPACE_URL, "renti-listing:" + listingId).toString();
    }

    /** point payload（snake_case，对齐旧 _listing_payload = asdict(listing) + 附加字段） */
    public Map<String, Object> listingPayload(ListingEntity listing) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", listing.getListingId());
        payload.put("city", nz(listing.getCity()));
        payload.put("district", nz(listing.getDistrict()));
        payload.put("business_area", nz(listing.getBusinessArea()));
        payload.put("community", nz(listing.getCommunity()));
        payload.put("title", nz(listing.getTitle()));
        payload.put("longitude", listing.getLongitude());
        payload.put("latitude", listing.getLatitude());
        payload.put("rent_price", listing.getRentPrice());
        payload.put("layout", nz(listing.getLayout()));
        payload.put("area_sqm", listing.getAreaSqm() == null ? 0 : listing.getAreaSqm());
        payload.put("rent_type", nz(listing.getRentType()));
        payload.put("nearest_metro", nz(listing.getNearestMetro()));
        payload.put("metro_distance_m", listing.getMetroDistanceM() == null ? 0 : listing.getMetroDistanceM());
        payload.put("commute_minutes", listing.getCommuteMinutes() == null ? 0 : listing.getCommuteMinutes());
        payload.put("tags", listing.getTags() == null ? List.of() : listing.getTags());
        payload.put("risk_tags", listing.getRiskTags() == null ? List.of() : listing.getRiskTags());
        payload.put("source", nz(listing.getSource()));
        payload.put("updated_at", listing.getUpdatedAt() == null ? ""
                : listing.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        payload.put("source_url", nz(listing.getSourceUrl()));
        payload.put("external_id", nz(listing.getExternalId()));
        payload.put("image", nz(listing.getImage()));
        payload.put("images", listing.getImages() == null ? List.of() : listing.getImages());
        payload.put("listing_id", listing.getListingId());
        payload.put("status", "active");
        payload.put("provider", !nz(listing.getProvider()).isEmpty()
                ? listing.getProvider() : nz(listing.getSource()));
        return payload;
    }

    /** 发布库加载（city 默认上海，query 模糊匹配标题/小区/区域） */
    public List<ListingEntity> loadPublishedListings(String city, String query, int limit) {
        var selectedCity = city == null || city.isBlank() ? "上海" : city.strip();
        var cleanQuery = query == null ? "" : query.strip().toLowerCase();
        Specification<ListingEntity> spec = (root, criteriaQuery, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("city"), selectedCity));
            predicates.add(cb.equal(root.get("status"), "active"));
            if (!cleanQuery.isEmpty()) {
                var like = "%" + cleanQuery + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("community")), like),
                        cb.like(cb.lower(root.get("district")), like),
                        cb.like(cb.lower(root.get("businessArea")), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return listingRepository
                .findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "updatedAt")))
                .getContent();
    }

    /** 对齐旧 _point_from_qdrant_row */
    private Map<String, Object> pointFromRow(Map<String, Object> row) {
        var payload = asMap(row.get("payload"));
        var document = text(payload.get("document"), "");
        var value = new LinkedHashMap<String, Object>();
        value.put("pointId", String.valueOf(row.get("id") == null ? "" : row.get("id")));
        value.put("listingId", text(payload.get("listing_id"), text(payload.get("id"), "")));
        value.put("title", text(payload.get("title"), ""));
        value.put("community", text(payload.get("community"), ""));
        value.put("city", text(payload.get("city"), ""));
        value.put("district", text(payload.get("district"), ""));
        value.put("businessArea", text(payload.get("business_area"), ""));
        value.put("rentPrice", payload.get("rent_price"));
        value.put("layout", text(payload.get("layout"), ""));
        value.put("provider", text(payload.get("provider"), ""));
        value.put("status", text(payload.get("status"), ""));
        value.put("document", document);
        value.put("documentPreview", document.length() > 260 ? document.substring(0, 260) : document);
        return value;
    }

    /** 对齐旧 _vector_params_from_collection_info（含 named vectors 兼容） */
    private Map<String, Object> vectorParams(Map<String, Object> info) {
        var config = asMap(info.get("config"));
        var params = asMap(config.get("params"));
        var vectors = asMap(params.get("vectors"));
        if (vectors.containsKey("size")) {
            return vectors;
        }
        for (var value : vectors.values()) {
            if (value instanceof Map<?, ?>) {
                return asMap(value);
            }
        }
        return Map.of();
    }

    /** 对齐旧 _raw_summary */
    private String rawSummary(Map<String, Object> raw) {
        if (raw == null) {
            return "";
        }
        var values = new ArrayList<String>();
        for (var key : List.of("description", "summary", "intro", "house_desc", "房源描述")) {
            var value = text(raw.get(key), "");
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        var joined = String.join("\n", values);
        return joined.length() > 1200 ? joined.substring(0, 1200) : joined;
    }

    /** RFC 4122 name-based UUID v5（SHA-1），等价 Python uuid.uuid5 */
    static UUID uuid5(UUID namespace, String name) {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            var buffer = new byte[16];
            longToBytes(namespace.getMostSignificantBits(), buffer, 0);
            longToBytes(namespace.getLeastSignificantBits(), buffer, 8);
            digest.update(buffer);
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            var hash = digest.digest();
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);
            long msb = 0;
            long lsb = 0;
            for (int index = 0; index < 8; index++) {
                msb = (msb << 8) | (hash[index] & 0xFF);
            }
            for (int index = 8; index < 16; index++) {
                lsb = (lsb << 8) | (hash[index] & 0xFF);
            }
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 unavailable", exception);
        }
    }

    private static void longToBytes(long value, byte[] target, int offset) {
        for (int index = 0; index < 8; index++) {
            target[offset + index] = (byte) (value >>> (8 * (7 - index)));
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static int bounded(Integer value, int fallback, int minimum, int maximum) {
        int parsed = value == null ? fallback : value;
        return Math.max(minimum, Math.min(maximum, parsed));
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

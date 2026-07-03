package com.renti.agent.modules.listing.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.infrastructure.persistence.repository.ListingCandidateRepository;
import com.renti.agent.infrastructure.persistence.repository.ListingRepository;
import com.renti.agent.modules.ingestion.domain.ListingNormalizer;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 房源管理：管理端已发布房源的列表/详情/编辑/删除，
 * 行为对齐旧 listing_ingestion.py 的 list_published/update_published/delete_published。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingAdminService {

    private static final Set<String> ALLOWED_STATUS = Set.of("active", "unavailable", "all");

    private final ListingRepository listingRepository;
    private final ListingCandidateRepository candidateRepository;
    private final ListingPayloadMapper payloadMapper;

    @Transactional(readOnly = true)
    public Map<String, Object> listPublished(Integer limit, Integer page, String status, String query, String city) {
        var allowedStatus = ALLOWED_STATUS.contains(String.valueOf(status)) ? status : "active";
        int pageSize = bounded(limit, 50, 1, 200);
        int pageNumber = bounded(page, 1, 1, 100_000);
        var spec = publishedSpec(allowedStatus, query, city);
        var result = listingRepository.findAll(spec,
                PageRequest.of(pageNumber - 1, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt")));

        var payload = new LinkedHashMap<String, Object>();
        payload.put("ok", true);
        payload.put("listings", result.getContent().stream().map(payloadMapper::publishedPayload).toList());
        payload.put("total", result.getTotalElements());
        payload.put("page", pageNumber);
        payload.put("pageSize", pageSize);
        payload.put("totalPages", totalPages(result.getTotalElements(), pageSize));
        return payload;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPublished(String listingId) {
        var cleanId = ListingNormalizer.cleanText(listingId, 120);
        return listingRepository.findById(cleanId)
                .<Map<String, Object>>map(entity -> Map.of("ok", true, "listing", payloadMapper.publishedPayload(entity)))
                .orElseGet(() -> error("not_found", "房源不存在或已被删除。"));
    }

    /** 用户端详情：仅 active 房源，含 PropertyDetail */
    @Transactional(readOnly = true)
    public Map<String, Object> publishedDetail(String listingId) {
        var cleanId = ListingNormalizer.cleanText(listingId, 120);
        var entity = listingRepository.findById(cleanId)
                .filter(listing -> "active".equals(listing.getStatus()))
                .orElse(null);
        if (entity == null) {
            return error("not_found", "房源不存在或当前不可用。");
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("ok", true);
        payload.put("listing", payloadMapper.publishedPayload(entity));
        payload.put("detail", payloadMapper.propertyDetail(entity));
        return payload;
    }

    @Transactional
    public Map<String, Object> updatePublished(String listingId, Map<String, Object> payload) {
        var cleanId = ListingNormalizer.cleanText(listingId, 120);
        if (cleanId.isEmpty()) {
            return error("invalid_listing_id", "房源 ID 无效。");
        }
        var entity = listingRepository.findById(cleanId).orElse(null);
        if (entity == null) {
            return error("not_found", "房源不存在或已被删除。");
        }

        var update = normalizeUpdate(entity, payload == null ? Map.of() : payload);
        @SuppressWarnings("unchecked")
        var fieldErrors = (Map<String, Object>) update.get("fieldErrors");
        if (!fieldErrors.isEmpty()) {
            return errorWithFields("invalid_listing", "房源字段校验失败。", fieldErrors);
        }

        @SuppressWarnings("unchecked")
        var listing = (Map<String, Object>) update.get("listing");
        var status = (String) update.get("status");
        var unavailableReason = (String) update.get("unavailableReason");

        payloadMapper.applyPayload(entity, listing);
        entity.setStatus(status);
        entity.setUnavailableReason(unavailableReason);
        entity.setUpdatedAt(OffsetDateTime.now());
        listingRepository.save(entity);

        if (entity.getCandidateId() != null) {
            candidateRepository.findById(entity.getCandidateId()).ifPresent(candidate -> {
                candidate.setPayload(new LinkedHashMap<>(listing));
                candidate.setUpdatedAt(OffsetDateTime.now());
                candidateRepository.save(candidate);
            });
        }
        log.info("Updated published listing {} status={}", cleanId, status);
        return Map.of(
                "ok", true,
                "listing", payloadMapper.publishedPayload(entity),
                "summary", "房源信息已更新。");
    }

    @Transactional
    public Map<String, Object> deletePublished(String listingId) {
        var cleanId = ListingNormalizer.cleanText(listingId, 120);
        if (cleanId.isEmpty()) {
            return error("invalid_listing_id", "房源 ID 无效。");
        }
        if (!listingRepository.existsById(cleanId)) {
            return error("not_found", "房源不存在或已被删除。");
        }
        listingRepository.deleteById(cleanId);
        log.info("Deleted published listing {}", cleanId);
        return Map.of(
                "ok", true,
                "deleted", true,
                "listingId", cleanId,
                "summary", "房源已从正式房源库删除。");
    }

    // ---------- 编辑白名单校验（旧 _normalize_published_update） ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeUpdate(ListingEntity entity, Map<String, Object> payload) {
        var source = payload.get("listing") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : payload;
        var listing = payloadMapper.listingPayload(entity);
        var fieldErrors = new LinkedHashMap<String, Object>();

        var textFields = new LinkedHashMap<String, Integer>();
        textFields.put("city", 40);
        textFields.put("district", 40);
        textFields.put("businessArea", 80);
        textFields.put("community", 120);
        textFields.put("title", 160);
        textFields.put("layout", 40);
        textFields.put("rentType", 40);
        textFields.put("nearestMetro", 80);
        textFields.put("sourceUrl", 500);
        textFields.put("image", 500);
        textFields.put("updatedAt", 40);
        var requiredTextFields = Set.of("city", "district", "businessArea", "community", "title", "layout", "rentType");
        for (var field : textFields.entrySet()) {
            var snake = camelToSnake(field.getKey());
            if (!source.containsKey(field.getKey()) && !source.containsKey(snake)) {
                continue;
            }
            var value = ListingNormalizer.cleanText(
                    ListingNormalizer.first(source, field.getKey(), snake), field.getValue());
            if (requiredTextFields.contains(field.getKey()) && value.isEmpty()) {
                fieldErrors.put(field.getKey(), "不能为空。");
                continue;
            }
            listing.put(field.getKey(), value);
        }

        var intFields = Map.of(
                "rentPrice", new int[]{1, 500_000},
                "areaSqm", new int[]{1, 10_000},
                "metroDistanceM", new int[]{0, 50_000},
                "commuteMinutes", new int[]{0, 500});
        for (var field : intFields.entrySet()) {
            var snake = camelToSnake(field.getKey());
            if (!source.containsKey(field.getKey()) && !source.containsKey(snake)) {
                continue;
            }
            int value = ListingNormalizer.intFrom(ListingNormalizer.first(source, field.getKey(), snake));
            int minimum = field.getValue()[0];
            int maximum = field.getValue()[1];
            if (value < minimum || value > maximum) {
                fieldErrors.put(field.getKey(), "请输入 " + minimum + "-" + maximum + " 之间的数字。");
                continue;
            }
            listing.put(field.getKey(), value);
        }

        if (source.containsKey("longitude")) {
            var value = ListingNormalizer.floatFrom(source.get("longitude"));
            if (value == null || value < -180.0 || value > 180.0) {
                fieldErrors.put("longitude", "坐标格式不正确。");
            } else {
                listing.put("longitude", value);
            }
        }
        if (source.containsKey("latitude")) {
            var value = ListingNormalizer.floatFrom(source.get("latitude"));
            if (value == null || value < -90.0 || value > 90.0) {
                fieldErrors.put("latitude", "坐标格式不正确。");
            } else {
                listing.put("latitude", value);
            }
        }

        for (var field : List.of("tags", "riskTags")) {
            var snake = camelToSnake(field);
            if (!source.containsKey(field) && !source.containsKey(snake)) {
                continue;
            }
            var values = ListingNormalizer.listFrom(ListingNormalizer.first(source, field, snake));
            listing.put(field, values.subList(0, Math.min(12, values.size())));
        }

        if (ListingNormalizer.hasImageListField(source)) {
            var images = ListingNormalizer.imageListFromSource(source);
            listing.put("images", images);
            listing.put("image", images.isEmpty()
                    ? ListingNormalizer.cleanText(listing.get("image"), 500) : images.get(0));
        } else {
            var merged = new ArrayList<String>();
            merged.add(ListingNormalizer.cleanText(listing.get("image"), 500));
            merged.addAll(ListingNormalizer.imageListFromSource(listing));
            var images = ListingNormalizer.dedupeTextValues(merged, null, 500);
            listing.put("images", images);
            if (ListingNormalizer.cleanText(listing.get("image"), 500).isEmpty() && !images.isEmpty()) {
                listing.put("image", images.get(0));
            }
        }

        listing.put("id", entity.getListingId());
        listing.put("provider", firstNonEmpty(text(listing.get("provider")), text(entity.getProvider())));
        listing.put("source", firstNonEmpty(text(listing.get("source")), text(entity.getSourceName())));
        listing.put("externalId", text(listing.get("externalId")));
        if (!(listing.get("raw") instanceof Map)) {
            listing.put("raw", new LinkedHashMap<String, Object>());
        }
        if (text(listing.get("updatedAt")).isEmpty()) {
            listing.put("updatedAt", ListingNormalizer.today());
        }

        for (var requiredField : ListingNormalizer.REQUIRED_FIELDS) {
            if (ListingNormalizer.isEmpty(listing.get(requiredField))) {
                fieldErrors.putIfAbsent(requiredField, "不能为空。");
            }
        }

        var rawStatus = ListingNormalizer.cleanText(
                payload.get("status") == null ? entity.getStatus() : payload.get("status"), 32);
        var status = Set.of("active", "unavailable").contains(rawStatus) ? rawStatus : "active";
        var unavailableReason = ListingNormalizer.cleanText(
                ListingNormalizer.first(payload, "unavailableReason", "unavailable_reason") == null
                        ? entity.getUnavailableReason()
                        : ListingNormalizer.first(payload, "unavailableReason", "unavailable_reason"),
                300);
        if ("active".equals(status)) {
            unavailableReason = "";
        } else if (unavailableReason.isEmpty()) {
            unavailableReason = "后台标记不可用";
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("listing", listing);
        result.put("status", status);
        result.put("unavailableReason", unavailableReason);
        result.put("fieldErrors", fieldErrors);
        return result;
    }

    // ---------- 列表过滤（旧 _published_filter_params + _query_tokens） ----------

    private Specification<ListingEntity> publishedSpec(String status, String query, String city) {
        var cleanCity = ListingNormalizer.cleanText(city, 40);
        var tokens = queryTokens(query);
        return (root, criteriaQuery, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (!"all".equals(status)) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (!cleanCity.isEmpty()) {
                predicates.add(builder.equal(root.get("city"), cleanCity));
            }
            if (!tokens.isEmpty()) {
                var tokenClauses = new ArrayList<Predicate>();
                for (var token : tokens) {
                    var needle = "%" + token.toLowerCase() + "%";
                    var fieldClauses = new ArrayList<Predicate>();
                    for (var field : List.of("listingId", "provider", "sourceName", "sourceUrl",
                            "title", "community", "district", "businessArea", "nearestMetro", "layout")) {
                        fieldClauses.add(builder.like(
                                builder.lower(builder.coalesce(root.get(field), "")), needle));
                    }
                    tokenClauses.add(builder.or(fieldClauses.toArray(Predicate[]::new)));
                }
                predicates.add(builder.or(tokenClauses.toArray(Predicate[]::new)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    static List<String> queryTokens(String value) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        normalized = normalized.replaceAll("\\d+(?:\\.\\d+)?\\s*(?:米|m|M|公里|千米|km|KM)", " ");
        normalized = normalized.replaceAll("(我想|想要|帮我|查找|查询|寻找|找|附近|周边|房源|信息|租房|上海市|上海|的)", " ");
        var tokens = new ArrayList<String>();
        for (var part : normalized.split("[\\s,，/、;；|]+")) {
            var token = ListingNormalizer.cleanText(part, 60);
            if (token.length() < 2) {
                continue;
            }
            if (!tokens.contains(token)) {
                tokens.add(token);
            }
            if (tokens.size() >= 6) {
                break;
            }
        }
        return tokens;
    }

    private static String camelToSnake(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonEmpty(String... values) {
        for (var value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static int bounded(Integer value, int defaultValue, int minimum, int maximum) {
        int parsed = value == null ? defaultValue : value;
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private static long totalPages(long total, int pageSize) {
        return total <= 0 ? 1 : (total + pageSize - 1) / pageSize;
    }

    static Map<String, Object> error(String code, String summary) {
        return errorWithFields(code, summary, Map.of());
    }

    static Map<String, Object> errorWithFields(String code, String summary, Map<String, Object> fieldErrors) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("ok", false);
        payload.put("code", code);
        payload.put("summary", summary);
        payload.put("fieldErrors", fieldErrors);
        return payload;
    }
}

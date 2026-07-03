package com.renti.agent.modules.ingestion.application;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.infrastructure.persistence.entity.ListingCandidateEntity;
import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.infrastructure.persistence.repository.ListingCandidateRepository;
import com.renti.agent.infrastructure.persistence.repository.ListingRepository;
import com.renti.agent.modules.ingestion.domain.ListingNormalizer;
import com.renti.agent.modules.listing.application.ListingPayloadMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采集数据种子：首次启动时从 classpath:seed 导入旧库数据。
 *
 * <p>先导入 candidates.json（renti_listing_candidates，453 条，含审核状态），
 * 记录旧候选 ID → 新 ID 的映射；再导入 listings.json（renti_listings，385 条已发布房源），
 * 按映射修复 candidateId 外链。旧 snake_case listing_json 统一转 camelCase payload。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionSeeder {

    /** 旧 listing_json / normalized_json 的 snake_case → camelCase 键映射 */
    private static final Map<String, String> KEY_MAPPING = Map.ofEntries(
            Map.entry("business_area", "businessArea"),
            Map.entry("rent_price", "rentPrice"),
            Map.entry("area_sqm", "areaSqm"),
            Map.entry("rent_type", "rentType"),
            Map.entry("nearest_metro", "nearestMetro"),
            Map.entry("metro_distance_m", "metroDistanceM"),
            Map.entry("commute_minutes", "commuteMinutes"),
            Map.entry("risk_tags", "riskTags"),
            Map.entry("updated_at", "updatedAt"),
            Map.entry("source_url", "sourceUrl"),
            Map.entry("external_id", "externalId"));

    private final ListingCandidateRepository candidateRepository;
    private final ListingRepository listingRepository;
    private final ListingPayloadMapper payloadMapper;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    @Transactional
    public void seed() {
        var candidateIdMapping = seedCandidates();
        seedListings(candidateIdMapping);
    }

    private Map<Long, Long> seedCandidates() {
        var mapping = new HashMap<Long, Long>();
        if (candidateRepository.count() > 0) {
            return mapping;
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(
                    new ClassPathResource("seed/candidates.json").getInputStream(),
                    new TypeReference<>() {
                    });
            int imported = 0;
            for (var row : rows) {
                var payload = camelPayload(parseJson(row.get("normalized_json")));
                var candidate = new ListingCandidateEntity();
                candidate.setJobId(longValue(row.get("job_id")));
                candidate.setStatus(text(row.get("status"), "pending"));
                candidate.setListingId(text(row.get("listing_id"), ""));
                candidate.setDedupeKey(text(row.get("dedupe_key"), ""));
                candidate.setPayload(payload);
                candidate.setReason(text(row.get("review_note"), ""));
                candidate.setCity(ListingNormalizer.cleanText(payload.get("city"), 40));
                candidate.setProvider(ListingNormalizer.cleanText(payload.get("provider"), 80));
                candidate.setExternalId(ListingNormalizer.cleanText(payload.get("externalId"), 180));
                candidate.setSourceUrl(ListingNormalizer.cleanText(payload.get("sourceUrl"), 2000));
                candidate.setCreatedAt(dateTime(row.get("created_at"), OffsetDateTime.now()));
                candidate.setUpdatedAt(dateTime(row.get("updated_at"), OffsetDateTime.now()));
                candidate.setReviewedAt(dateTime(row.get("reviewed_at"), null));
                var saved = candidateRepository.save(candidate);
                var oldId = longValue(row.get("id"));
                if (oldId != null) {
                    mapping.put(oldId, saved.getId());
                }
                imported++;
            }
            log.info("Seeded {} listing candidates from seed/candidates.json", imported);
        } catch (Exception exception) {
            log.error("Failed to seed listing candidates", exception);
        }
        return mapping;
    }

    private void seedListings(Map<Long, Long> candidateIdMapping) {
        if (listingRepository.count() > 0) {
            return;
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(
                    new ClassPathResource("seed/listings.json").getInputStream(),
                    new TypeReference<>() {
                    });
            int imported = 0;
            for (var row : rows) {
                var payload = camelPayload(parseJson(row.get("listing_json")));
                var entity = new ListingEntity();
                payloadMapper.applyPayload(entity, payload);
                entity.setListingId(text(row.get("listing_id"), String.valueOf(payload.get("id"))));
                entity.setCity(text(row.get("city"), ListingNormalizer.cleanText(payload.get("city"), 32)));
                entity.setStatus(text(row.get("status"), "active"));
                entity.setProvider(text(row.get("provider"), entity.getProvider()));
                entity.setExternalId(text(row.get("external_id"), entity.getExternalId()));
                entity.setSourceUrl(text(row.get("source_url"), entity.getSourceUrl()));
                entity.setSourceName(text(row.get("source_name"), entity.getSource()));
                entity.setUnavailableReason(text(row.get("unavailable_reason"), ""));
                var oldCandidateId = longValue(row.get("candidate_id"));
                entity.setCandidateId(oldCandidateId == null ? null : candidateIdMapping.get(oldCandidateId));
                entity.setPublishedAt(dateTime(row.get("published_at"), OffsetDateTime.now()));
                entity.setUpdatedAt(dateTime(row.get("updated_at"), OffsetDateTime.now()));
                listingRepository.save(entity);
                imported++;
            }
            log.info("Seeded {} published listings from seed/listings.json", imported);
        } catch (Exception exception) {
            log.error("Failed to seed published listings", exception);
        }
    }

    // ---------- 工具 ----------

    private Map<String, Object> parseJson(Object value) {
        try {
            return objectMapper.readValue(String.valueOf(value == null ? "{}" : value),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    /** 旧 snake_case payload → camelCase（raw 保持原样） */
    private Map<String, Object> camelPayload(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            result.put(KEY_MAPPING.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static String text(Object value, String fallback) {
        if (value == null || String.valueOf(value).isEmpty()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static OffsetDateTime dateTime(Object value, OffsetDateTime fallback) {
        if (value == null || String.valueOf(value).isEmpty()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (Exception exception) {
            return fallback;
        }
    }
}

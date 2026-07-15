package com.renti.agent.modules.ingestion.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.infrastructure.persistence.entity.IngestionJobEntity;
import com.renti.agent.infrastructure.persistence.entity.ListingCandidateEntity;
import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.infrastructure.persistence.repository.IngestionJobRepository;
import com.renti.agent.infrastructure.persistence.repository.ListingCandidateRepository;
import com.renti.agent.infrastructure.persistence.repository.ListingRepository;
import com.renti.agent.modules.ingestion.domain.ListingNormalizer;
import com.renti.agent.modules.listing.application.ListingPayloadMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采集审核流水线：导入 → 候选去重 → 人工/批量审核 → 发布到正式房源库。
 *
 * <p>行为对齐旧 listing_ingestion.py（SqlListingIngestionStore）：dedupeKey 去重规则、
 * approved 状态保持、坐标回填、cleanupMissing 清理、审核发布与概览统计。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private static final String CLEANUP_NOTE = "最新采集未命中，疑似已出租或已下架";
    private static final Sort UPDATED_DESC = Sort.by(Sort.Direction.DESC, "updatedAt");

    private final ListingCandidateRepository candidateRepository;
    private final IngestionJobRepository jobRepository;
    private final ListingRepository listingRepository;
    private final ListingPayloadMapper payloadMapper;
    private final ObjectMapper objectMapper;

    // ---------- 导入 ----------

    /** 手动/爬虫导入：JSON 数组 → 候选（对齐旧 import_rows，响应合并 overview） */
    @Transactional
    public Map<String, Object> importRows(Map<String, Object> payload) {
        List<Map<String, Object>> rows;
        try {
            rows = parseImportItems(payload);
        } catch (IllegalArgumentException exception) {
            return error("invalid_import", exception.getMessage());
        }
        var sourceName = ListingNormalizer.cleanText(
                orDefault(ListingNormalizer.first(payload, "sourceName", "source"), ListingNormalizer.DEFAULT_SOURCE_NAME), 80);
        var provider = ListingNormalizer.cleanText(
                orDefault(payload.get("provider"), ListingNormalizer.DEFAULT_PROVIDER), 80);
        var sourceType = ListingNormalizer.cleanText(orDefault(payload.get("sourceType"), "manual_upload"), 40);
        var city = ListingNormalizer.cleanText(orDefault(payload.get("city"), "上海"), 40);
        var jobType = ListingNormalizer.cleanText(orDefault(payload.get("jobType"),
                "public_listing_page".equals(sourceType) ? "crawler" : "manual_import"), 64);
        boolean cleanupMissing = Boolean.TRUE.equals(payload.get("cleanupMissing"));

        var job = new IngestionJobEntity();
        job.setSourceName(sourceName);
        job.setProvider(provider);
        job.setSourceType(sourceType);
        job.setBaseUrl(ListingNormalizer.cleanText(payload.get("baseUrl"), 500));
        job.setJobType(jobType);
        job.setCity(city);
        job.setStatus("running");
        job.setTotalInput(rows.size());
        job.setStartedAt(OffsetDateTime.now());
        job = jobRepository.save(job);

        int created = 0;
        int updated = 0;
        int rejected = 0;
        int autoSynced = 0;
        int mergedDuplicates = 0;
        int coordinateBackfilled = 0;
        var seenListingIds = new ArrayList<String>();
        var context = Map.of("sourceName", sourceName, "provider", provider, "city", city);

        for (var row : rows) {
            var normalized = ListingNormalizer.normalize(row, context);
            var listing = normalized.listing();
            if (backfillMissingCoordinates(listing)) {
                coordinateBackfilled++;
            }
            var quality = ListingNormalizer.qualityReport(listing);
            var result = upsertCandidate(job.getId(), listing, normalized.dedupeKey(), normalized.fingerprint());
            seenListingIds.add(String.valueOf(listing.get("id")));

            // 跨源去重：新候选若命中其他来源的同物理指纹，则并入主记录、不重复入库/发布
            if (result.created() && "pending".equals(result.candidate().getStatus())
                    && mergeCrossSourceDuplicate(result.candidate(), normalized.fingerprint(), provider)) {
                mergedDuplicates++;
                continue;
            }

            if (result.created()) {
                created++;
            } else {
                updated++;
            }
            boolean publishable = Boolean.TRUE.equals(quality.get("publishable"));
            if (!publishable) {
                rejected++;
            }
            if ("approved".equals(result.candidate().getStatus()) && publishable) {
                publish(result.candidate());
                autoSynced++;
            }
        }

        var cleanup = cleanupMissingSourceItems(sourceName, provider, city, seenListingIds, job.getId(), cleanupMissing);

        job.setStatus("completed");
        job.setCandidatesCreated(created);
        job.setCandidatesUpdated(updated);
        job.setFinishedAt(OffsetDateTime.now());
        jobRepository.save(job);
        log.info("Ingestion import job {} done: {} created, {} updated", job.getId(), created, updated);

        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("jobId", job.getId());
        response.put("totalInput", rows.size());
        response.put("candidatesCreated", created);
        response.put("candidatesUpdated", updated);
        response.put("mergedDuplicates", mergedDuplicates);
        response.put("qualityBlocked", rejected);
        response.put("publishedSynced", autoSynced);
        response.put("coordinateBackfilled", coordinateBackfilled);
        response.put("staleCandidatesRejected", cleanup.get("staleCandidatesRejected"));
        response.put("unavailableListings", cleanup.get("unavailableListings"));
        response.put("summary", importSummary(rows.size(), created, updated, rejected, autoSynced,
                coordinateBackfilled, mergedDuplicates, cleanup));
        overview().forEach(response::putIfAbsent);
        return response;
    }

    /** 采集失败也留痕：记录一条 failed 任务（爬虫抓取为空/异常时调用） */
    @Transactional
    public IngestionJobEntity recordFailedJob(String sourceName, String provider, String sourceType,
                                              String baseUrl, String city, String errorMessage) {
        var job = new IngestionJobEntity();
        job.setSourceName(sourceName);
        job.setProvider(provider);
        job.setSourceType(sourceType);
        job.setBaseUrl(ListingNormalizer.cleanText(baseUrl, 500));
        job.setJobType("crawler");
        job.setCity(city);
        job.setStatus("failed");
        job.setErrorMessage(ListingNormalizer.cleanText(errorMessage, 2000));
        job.setStartedAt(OffsetDateTime.now());
        job.setFinishedAt(OffsetDateTime.now());
        return jobRepository.save(job);
    }

    // ---------- 概览与候选列表 ----------

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        var counts = new LinkedHashMap<String, Object>();
        counts.put("pending", candidateRepository.countByStatus("pending"));
        counts.put("approved", candidateRepository.countByStatus("approved"));
        counts.put("rejected", candidateRepository.countByStatus("rejected"));
        counts.put("published", listingRepository.countByStatus("active"));
        counts.put("unavailable", listingRepository.countByStatus("unavailable"));

        var recentJobs = jobRepository.findTop6ByOrderByCreatedAtDesc().stream()
                .map(this::jobPayload)
                .toList();
        var sources = jobRepository.findLatestPerSource().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(20)
                .map(this::sourcePayload)
                .toList();

        var payload = new LinkedHashMap<String, Object>();
        payload.put("ok", true);
        payload.put("counts", counts);
        payload.put("recentJobs", recentJobs);
        payload.put("sources", sources);
        return payload;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> candidatesPayload(String status, Integer limit, Integer page) {
        var allowedStatus = allowedCandidateStatus(status);
        int pageSize = bounded(limit, 50, 1, 200);
        int pageNumber = bounded(page, 1, 1, 100_000);
        var pageable = PageRequest.of(pageNumber - 1, pageSize, UPDATED_DESC);
        var result = "all".equals(allowedStatus)
                ? candidateRepository.findAll(pageable)
                : candidateRepository.findByStatus(allowedStatus, pageable);

        var payload = new LinkedHashMap<String, Object>();
        payload.put("ok", true);
        payload.put("candidates", result.getContent().stream().map(this::candidatePayload).toList());
        payload.put("total", result.getTotalElements());
        payload.put("page", pageNumber);
        payload.put("pageSize", pageSize);
        payload.put("totalPages", totalPages(result.getTotalElements(), pageSize));
        return payload;
    }

    // ---------- 审核 ----------

    @Transactional
    public Map<String, Object> approve(long candidateId) {
        var candidate = candidateRepository.findById(candidateId).orElse(null);
        if (candidate == null) {
            return error("not_found", "候选房源不存在。");
        }
        var quality = ListingNormalizer.qualityReport(candidate.getPayload());
        if (!Boolean.TRUE.equals(quality.get("publishable"))) {
            return errorWithFields("not_publishable", "候选房源缺少必要字段，无法发布。",
                    Map.of("quality", quality));
        }
        publish(candidate);
        markReviewed(candidate, "approved", "");
        log.info("Approved candidate {} -> listing {}", candidateId, candidate.getListingId());
        return Map.of(
                "ok", true,
                "listing", candidate.getPayload(),
                "summary", "候选房源已发布到正式房源库。");
    }

    @Transactional
    public Map<String, Object> reject(long candidateId, String note) {
        var candidate = candidateRepository.findById(candidateId).orElse(null);
        if (candidate == null) {
            return error("not_found", "候选房源不存在。");
        }
        markReviewed(candidate, "rejected", ListingNormalizer.cleanText(note, 240));
        log.info("Rejected candidate {}", candidateId);
        return Map.of("ok", true, "summary", "候选房源已驳回。");
    }

    @Transactional
    public Map<String, Object> bulkApprove(Map<String, Object> payload) {
        var candidates = candidatesForBulk(payload);
        int approved = 0;
        int skipped = 0;
        for (var candidate : candidates) {
            var quality = ListingNormalizer.qualityReport(candidate.getPayload());
            if (!Boolean.TRUE.equals(quality.get("publishable"))) {
                skipped++;
                continue;
            }
            publish(candidate);
            markReviewed(candidate, "approved", "");
            approved++;
        }
        log.info("Bulk approve: {} approved, {} skipped", approved, skipped);
        return Map.of(
                "ok", true,
                "approved", approved,
                "skipped", skipped,
                "summary", "一键发布完成：发布 " + approved + " 条，跳过 " + skipped + " 条不可发布候选。");
    }

    @Transactional
    public Map<String, Object> bulkReject(Map<String, Object> payload) {
        var note = ListingNormalizer.cleanText(orDefault(payload.get("note"), "后台批量驳回"), 240);
        var candidates = candidatesForBulk(payload);
        int rejected = 0;
        for (var candidate : candidates) {
            markReviewed(candidate, "rejected", note);
            markListingsUnavailableByCandidate(candidate.getId(), note);
            rejected++;
        }
        log.info("Bulk reject: {} candidates", rejected);
        return Map.of(
                "ok", true,
                "rejected", rejected,
                "summary", "一键驳回完成：处理 " + rejected + " 条候选。");
    }

    // ---------- 发布 ----------

    /** 候选 → 正式房源库（对齐旧 _publish_listing_dict 的 upsert 语义） */
    @Transactional
    public ListingEntity publish(ListingCandidateEntity candidate) {
        var listing = candidate.getPayload();
        var listingId = ListingNormalizer.cleanText(listing.get("id"), 64);
        var entity = listingRepository.findById(listingId).orElseGet(ListingEntity::new);
        boolean isNew = entity.getListingId() == null;
        payloadMapper.applyPayload(entity, listing);
        entity.setSourceName(ListingNormalizer.cleanText(listing.get("source"), 128));
        entity.setStatus("active");
        entity.setUnavailableReason("");
        entity.setCandidateId(candidate.getId());
        if (isNew || entity.getPublishedAt() == null) {
            entity.setPublishedAt(OffsetDateTime.now());
        }
        entity.setUpdatedAt(OffsetDateTime.now());
        return listingRepository.save(entity);
    }

    // ---------- 响应结构 ----------

    /** 候选响应结构（旧 _candidate_from_row，quality 由 payload 实时计算） */
    public Map<String, Object> candidatePayload(ListingCandidateEntity candidate) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", candidate.getId());
        payload.put("jobId", candidate.getJobId());
        payload.put("status", candidate.getStatus());
        payload.put("dedupeKey", candidate.getDedupeKey());
        payload.put("listing", candidate.getPayload());
        payload.put("quality", ListingNormalizer.qualityReport(candidate.getPayload()));
        payload.put("reviewNote", candidate.getReason() == null ? "" : candidate.getReason());
        payload.put("createdAt", dateTimeText(candidate.getCreatedAt()));
        payload.put("updatedAt", dateTimeText(candidate.getUpdatedAt()));
        payload.put("reviewedAt", dateTimeText(candidate.getReviewedAt()));
        return payload;
    }

    private Map<String, Object> jobPayload(IngestionJobEntity job) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", job.getId());
        payload.put("jobType", job.getJobType());
        payload.put("city", job.getCity());
        payload.put("status", job.getStatus());
        payload.put("totalInput", job.getTotalInput());
        payload.put("candidatesCreated", job.getCandidatesCreated());
        payload.put("candidatesUpdated", job.getCandidatesUpdated());
        payload.put("errorMessage", job.getErrorMessage() == null ? "" : job.getErrorMessage());
        payload.put("createdAt", dateTimeText(job.getCreatedAt()));
        payload.put("finishedAt", dateTimeText(job.getFinishedAt()));
        return payload;
    }

    private Map<String, Object> sourcePayload(IngestionJobEntity job) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", job.getId());
        payload.put("name", job.getSourceName());
        payload.put("provider", job.getProvider());
        payload.put("sourceType", job.getSourceType());
        payload.put("enabled", true);
        payload.put("updatedAt", dateTimeText(job.getCreatedAt()));
        return payload;
    }

    // ---------- 内部实现 ----------

    private record UpsertResult(ListingCandidateEntity candidate, boolean created) {
    }

    private UpsertResult upsertCandidate(Long jobId, Map<String, Object> listing, String dedupeKey, String fingerprint) {
        var existing = candidateRepository.findByDedupeKey(dedupeKey).orElse(null);
        var candidate = existing == null ? new ListingCandidateEntity() : existing;
        candidate.setJobId(jobId);
        candidate.setDedupeKey(dedupeKey);
        candidate.setFingerprint(fingerprint);
        candidate.setListingId(ListingNormalizer.cleanText(listing.get("id"), 96));
        candidate.setPayload(new LinkedHashMap<>(listing));
        candidate.setCity(ListingNormalizer.cleanText(listing.get("city"), 40));
        candidate.setProvider(ListingNormalizer.cleanText(listing.get("provider"), 80));
        candidate.setExternalId(ListingNormalizer.cleanText(listing.get("externalId"), 180));
        candidate.setSourceUrl(ListingNormalizer.cleanText(listing.get("sourceUrl"), 2000));
        candidate.setUpdatedAt(OffsetDateTime.now());
        if (existing == null) {
            candidate.setStatus("pending");
        } else if (!"approved".equals(existing.getStatus())) {
            candidate.setStatus("pending");
        }
        return new UpsertResult(candidateRepository.save(candidate), existing == null);
    }

    /**
     * 跨源去重：新候选若与其他来源的同物理指纹房源命中，则并入主记录——
     * 把本源作为 altSource 记入主记录，本候选标记为 duplicate（不进审核队列、不重复发布）。
     * 若本源带官方核验旗标而主记录没有，则用本源信息升级主记录（并在主记录已发布时同步回房源库）。
     *
     * @return 是否已作为重复项并入
     */
    @SuppressWarnings("unchecked")
    private boolean mergeCrossSourceDuplicate(ListingCandidateEntity candidate, String fingerprint, String provider) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }
        var primary = candidateRepository.findCrossSourcePrimary(fingerprint, provider).orElse(null);
        if (primary == null || primary.getId().equals(candidate.getId())) {
            return false;
        }

        var dupPayload = candidate.getPayload();
        var primaryPayload = primary.getPayload();
        var primaryRaw = primaryPayload.get("raw") instanceof Map<?, ?> map
                ? new LinkedHashMap<String, Object>((Map<String, Object>) map) : new LinkedHashMap<String, Object>();

        var altSources = primaryRaw.get("altSources") instanceof List<?> list
                ? new ArrayList<Object>(list) : new ArrayList<Object>();
        var alt = new LinkedHashMap<String, Object>();
        alt.put("provider", dupPayload.get("provider"));
        alt.put("source", dupPayload.get("source"));
        alt.put("sourceUrl", dupPayload.get("sourceUrl"));
        alt.put("rentPrice", dupPayload.get("rentPrice"));
        alt.put("govCertified", rawFlag(dupPayload, "gov_certified"));
        boolean alreadyRecorded = altSources.stream()
                .anyMatch(item -> item instanceof Map<?, ?> map
                        && String.valueOf(map.get("provider")).equals(String.valueOf(dupPayload.get("provider"))));
        if (!alreadyRecorded) {
            altSources.add(alt);
        }
        primaryRaw.put("altSources", altSources);

        // 若本源官方核验而主记录未标注，则升级主记录的核验旗标
        boolean upgraded = false;
        if (rawFlag(dupPayload, "gov_certified") && !rawFlag(primaryPayload, "gov_certified")) {
            primaryRaw.put("gov_certified", true);
            upgraded = true;
        }
        primaryPayload.put("raw", primaryRaw);
        primary.setPayload(primaryPayload);
        primary.setUpdatedAt(OffsetDateTime.now());
        candidateRepository.save(primary);
        if (upgraded && "approved".equals(primary.getStatus())) {
            publish(primary);
        }

        candidate.setStatus("duplicate");
        candidate.setReason("跨源重复，已并入 " + primary.getProvider() + " 候选 #" + primary.getId());
        candidate.setReviewedAt(OffsetDateTime.now());
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidateRepository.save(candidate);
        log.info("Cross-source dedupe: candidate {} ({}) merged into primary {} ({})",
                candidate.getId(), provider, primary.getId(), primary.getProvider());
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean rawFlag(Map<String, Object> payload, String key) {
        if (Boolean.TRUE.equals(payload.get(key))) {
            return true;
        }
        return payload.get("raw") instanceof Map<?, ?> map && Boolean.TRUE.equals(((Map<String, Object>) map).get(key));
    }

    private void markReviewed(ListingCandidateEntity candidate, String status, String reason) {
        candidate.setStatus(status);
        candidate.setReason(reason);
        candidate.setReviewedAt(OffsetDateTime.now());
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidateRepository.save(candidate);
    }

    private void markListingsUnavailableByCandidate(Long candidateId, String note) {
        Specification<ListingEntity> spec = (root, query, builder) -> builder.and(
                builder.equal(root.get("candidateId"), candidateId),
                builder.equal(root.get("status"), "active"));
        for (var listing : listingRepository.findAll(spec)) {
            listing.setStatus("unavailable");
            listing.setUnavailableReason(note);
            listing.setUpdatedAt(OffsetDateTime.now());
            listingRepository.save(listing);
        }
    }

    private List<ListingCandidateEntity> candidatesForBulk(Map<String, Object> payload) {
        var ids = idList(payload.get("candidateIds") != null ? payload.get("candidateIds") : payload.get("ids"));
        int limit = bounded(intOrNull(payload.get("limit")), 200, 1, 500);
        if (!ids.isEmpty()) {
            return ids.stream()
                    .limit(limit)
                    .map(candidateRepository::findById)
                    .flatMap(Optional::stream)
                    .toList();
        }
        var status = allowedCandidateStatus(String.valueOf(orDefault(payload.get("status"), "pending")));
        var pageable = PageRequest.of(0, limit, UPDATED_DESC);
        return "all".equals(status)
                ? candidateRepository.findAll(pageable).getContent()
                : candidateRepository.findByStatus(status, pageable).getContent();
    }

    /** 坐标回填：同小区已有候选/已发布房源的坐标复用（对齐旧 _backfill_missing_coordinates） */
    private boolean backfillMissingCoordinates(Map<String, Object> listing) {
        if (!ListingNormalizer.isEmpty(listing.get("longitude"))
                && !ListingNormalizer.isEmpty(listing.get("latitude"))) {
            return false;
        }
        var city = ListingNormalizer.cleanText(listing.get("city"), 40);
        var community = ListingNormalizer.cleanText(listing.get("community"), 120);
        if (community.isEmpty()) {
            return false;
        }

        for (var known : candidateRepository.findRecentByCommunity(community)) {
            var knownListing = known.getPayload();
            if (sameCity(knownListing.get("city"), city)
                    && !ListingNormalizer.isEmpty(knownListing.get("longitude"))
                    && !ListingNormalizer.isEmpty(knownListing.get("latitude"))) {
                applyKnownCoordinates(listing, knownListing.get("longitude"), knownListing.get("latitude"),
                        "known_candidate");
                return true;
            }
        }

        Specification<ListingEntity> spec = (root, query, builder) ->
                builder.equal(root.get("community"), community);
        var published = listingRepository.findAll(spec, PageRequest.of(0, 50, UPDATED_DESC)).getContent();
        for (var known : published) {
            if ((city.isEmpty() || city.equals(known.getCity()))
                    && known.getLongitude() != 0.0 && known.getLatitude() != 0.0) {
                applyKnownCoordinates(listing, known.getLongitude(), known.getLatitude(),
                        "known_published_listing");
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void applyKnownCoordinates(Map<String, Object> listing, Object longitude, Object latitude, String source) {
        listing.put("longitude", longitude);
        listing.put("latitude", latitude);
        var raw = listing.get("raw") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<String, Object>();
        raw.put("coordinate_source", source);
        listing.put("raw", raw);
    }

    /** cleanupMissing：最新采集未命中的候选驳回、已发布房源下架（对齐旧 _cleanup_missing_source_items） */
    private Map<String, Integer> cleanupMissingSourceItems(String sourceName, String provider, String city,
                                                           List<String> seenListingIds, Long jobId, boolean enabled) {
        if (!enabled) {
            return Map.of("staleCandidatesRejected", 0, "unavailableListings", 0);
        }
        var seen = new HashSet<>(seenListingIds);
        var jobIds = jobRepository.findBySourceNameAndCity(sourceName, city).stream()
                .map(IngestionJobEntity::getId)
                .toList();

        int staleCandidates = 0;
        if (!jobIds.isEmpty()) {
            for (var candidate : candidateRepository.findByStatusAndJobIdIn("pending", jobIds)) {
                var listingId = candidate.getListingId() == null ? "" : candidate.getListingId();
                if (listingId.isEmpty() || seen.contains(listingId)) {
                    continue;
                }
                markReviewed(candidate, "rejected", CLEANUP_NOTE);
                staleCandidates++;
            }
        }

        Specification<ListingEntity> spec = (root, query, builder) -> builder.and(
                builder.equal(root.get("provider"), provider),
                builder.equal(root.get("sourceName"), sourceName),
                builder.equal(root.get("city"), city),
                builder.equal(root.get("status"), "active"));
        int unavailable = 0;
        for (var listing : listingRepository.findAll(spec)) {
            if (seen.contains(listing.getListingId())) {
                continue;
            }
            listing.setStatus("unavailable");
            listing.setUnavailableReason(CLEANUP_NOTE);
            listing.setUpdatedAt(OffsetDateTime.now());
            listingRepository.save(listing);
            unavailable++;
        }
        return Map.of("staleCandidatesRejected", staleCandidates, "unavailableListings", unavailable);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseImportItems(Map<String, Object> payload) {
        if (payload.get("items") instanceof List<?> items) {
            var rows = items.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        var content = ListingNormalizer.cleanText(
                ListingNormalizer.first(payload, "content", "itemsText"), 10_000_000);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("请粘贴 JSON/CSV 内容，或上传数据文件。");
        }
        if (!content.startsWith("[") && !content.startsWith("{")) {
            throw new IllegalArgumentException("JSON 内容需要是对象数组，或包含 items/listings 数组。");
        }
        try {
            Object parsed = objectMapper.readValue(content, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                var inner = map.get("items") != null ? map.get("items") : map.get("listings");
                parsed = inner != null ? inner : List.of(map);
            }
            if (!(parsed instanceof List<?> list)) {
                throw new IllegalArgumentException("JSON 内容需要是对象数组，或包含 items/listings 数组。");
            }
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("JSON 解析失败：" + exception.getMessage());
        }
    }

    private static String importSummary(int total, int created, int updated, int blocked,
                                        int synced, int coordinateBackfilled, int mergedDuplicates,
                                        Map<String, Integer> cleanup) {
        var parts = new ArrayList<String>();
        parts.add("已导入 " + total + " 条");
        parts.add("新增候选 " + created + " 条");
        parts.add("更新 " + updated + " 条");
        if (mergedDuplicates > 0) {
            parts.add("跨源合并 " + mergedDuplicates + " 条");
        }
        if (blocked > 0) {
            parts.add("暂不可发布 " + blocked + " 条");
        }
        if (coordinateBackfilled > 0) {
            parts.add("复用坐标 " + coordinateBackfilled + " 条");
        }
        if (synced > 0) {
            parts.add("同步已发布 " + synced + " 条");
        }
        if (cleanup.getOrDefault("staleCandidatesRejected", 0) > 0) {
            parts.add("清理候选 " + cleanup.get("staleCandidatesRejected") + " 条");
        }
        if (cleanup.getOrDefault("unavailableListings", 0) > 0) {
            parts.add("标记下架 " + cleanup.get("unavailableListings") + " 条");
        }
        return String.join("，", parts) + "。";
    }

    private static boolean sameCity(Object knownCity, String city) {
        return city.isEmpty() || city.equals(String.valueOf(knownCity == null ? "" : knownCity));
    }

    private static String allowedCandidateStatus(String status) {
        return Set.of("pending", "approved", "rejected", "all").contains(String.valueOf(status))
                ? status : "pending";
    }

    private static List<Long> idList(Object value) {
        var result = new ArrayList<Long>();
        if (value instanceof List<?> list) {
            for (var item : list) {
                try {
                    result.add(Long.parseLong(String.valueOf(item).trim()));
                } catch (NumberFormatException ignored) {
                    // 跳过非法 ID，对齐旧行为
                }
            }
        }
        return result;
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Object orDefault(Object value, Object fallback) {
        return value == null || "".equals(value) ? fallback : value;
    }

    private static int bounded(Integer value, int defaultValue, int minimum, int maximum) {
        int parsed = value == null ? defaultValue : value;
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private static long totalPages(long total, int pageSize) {
        return total <= 0 ? 1 : (total + pageSize - 1) / pageSize;
    }

    private static String dateTimeText(OffsetDateTime value) {
        return value == null ? "" : value.toString();
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

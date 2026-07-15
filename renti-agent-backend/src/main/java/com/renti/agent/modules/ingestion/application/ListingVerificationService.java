package com.renti.agent.modules.ingestion.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.infrastructure.persistence.repository.ListingRepository;
import com.renti.agent.modules.ingestion.domain.ListingNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 房源核验器（入库后异步批量核验）：定期扫描已发布房源，评定官方核验状态并写入 verified/verifiedAt。
 *
 * <p>分级（无登录、诚实标注）：
 * <ul>
 *   <li>official_confirmed —— 房源带核验编号，调官方接口查得 status=1 且小区一致；</li>
 *   <li>official_failed —— 官方查得未通过(status=2) 或信息明显不符；</li>
 *   <li>platform_certified —— 无核验编号，但来源平台自标「官方核验」旗标（贝壳/链家法规强制）；</li>
 *   <li>unverified —— 无任何核验信号。</li>
 * </ul>
 * 贝壳/链家详情页需登录，取不到核验编号，故落 platform_certified；安居客/品牌公寓等公开带编号的可升到
 * official_confirmed。核验明细写入 listing.raw.verification 以便溯源。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingVerificationService {

    /** 核验有效期：30 天后重新核验（房源可能被官方撤销核验）。 */
    private static final long REVERIFY_DAYS = 30;
    private static final int DEFAULT_BATCH = 40;

    private final ListingRepository listingRepository;
    private final HouseVerificationClient verificationClient;

    /** 每 30 分钟核验一批（RENTAI_VERIFIER=0/false 可关闭）。 */
    @Scheduled(fixedDelay = 30 * 60_000, initialDelay = 90_000)
    public void scheduledVerify() {
        var flag = System.getenv("RENTAI_VERIFIER");
        if ("0".equals(flag) || "false".equalsIgnoreCase(flag)) {
            return;
        }
        try {
            var result = verifyBatch(DEFAULT_BATCH);
            if ((int) result.getOrDefault("processed", 0) > 0) {
                log.info("[verifier] 核验一批：{}", result.get("summary"));
            }
        } catch (Exception exception) {
            log.error("[verifier] 定时核验失败", exception);
        }
    }

    /** 核验一批待核验房源，返回统计。 */
    @Transactional
    public Map<String, Object> verifyBatch(int limit) {
        int batch = Math.max(1, Math.min(200, limit));
        var staleBefore = OffsetDateTime.now().minusDays(REVERIFY_DAYS);
        var listings = listingRepository.findNeedingVerification(staleBefore, PageRequest.of(0, batch));

        int officialConfirmed = 0;
        int officialFailed = 0;
        int platformCertified = 0;
        int unverified = 0;
        int errors = 0;

        for (var listing : listings) {
            String outcome;
            try {
                outcome = verifyOne(listing);
            } catch (Exception exception) {
                errors++;
                log.warn("[verifier] 房源 {} 核验异常：{}", listing.getListingId(), exception.getMessage());
                continue;
            }
            switch (outcome) {
                case "official_confirmed" -> officialConfirmed++;
                case "official_failed" -> officialFailed++;
                case "platform_certified" -> platformCertified++;
                default -> unverified++;
            }
        }

        int processed = officialConfirmed + officialFailed + platformCertified + unverified;
        var summary = "核验 " + processed + " 条：官方确认 " + officialConfirmed + "，官方未通过 " + officialFailed
                + "，平台核验 " + platformCertified + "，未核验 " + unverified
                + (errors > 0 ? "，异常 " + errors : "");
        var result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("processed", processed);
        result.put("officialConfirmed", officialConfirmed);
        result.put("officialFailed", officialFailed);
        result.put("platformCertified", platformCertified);
        result.put("unverified", unverified);
        result.put("errors", errors);
        result.put("summary", summary);
        return result;
    }

    /** 核验单条，写回 verified/verifiedAt/raw.verification，返回结果码。 */
    private String verifyOne(ListingEntity listing) {
        var raw = listing.getRaw() == null ? new LinkedHashMap<String, Object>() : listing.getRaw();
        var checknum = extractCheckNum(raw);
        String outcome;
        var detail = new LinkedHashMap<String, Object>();

        if (!checknum.isEmpty()) {
            var result = verificationClient.verify(checknum); // 网络失败会抛异常，由上层计入 errors
            if (result.isPresent()) {
                var verification = result.get();
                detail.put("checknum", checknum);
                detail.put("officialStatus", verification.status());
                detail.put("officialCommunity", verification.communityName());
                detail.put("officialArea", verification.area());
                if (verification.passed() && communityMatches(listing.getCommunity(), verification.communityName())) {
                    outcome = "official_confirmed";
                } else if (verification.status() == 2) {
                    outcome = "official_failed";
                    detail.put("reason", "官方核验未通过");
                } else if (verification.passed()) {
                    outcome = "official_failed";
                    detail.put("reason", "官方核验通过但小区信息不符：" + verification.communityName());
                } else {
                    outcome = platformFallback(raw);
                    detail.put("reason", "官方核验中(status=0)");
                }
            } else {
                detail.put("checknum", checknum);
                outcome = platformFallback(raw);
                detail.put("reason", "官方未查到该核验编号");
            }
        } else {
            outcome = platformFallback(raw);
        }

        raw.put("verification", detail);
        listing.setRaw(raw);
        listing.setVerified(outcome);
        listing.setVerifiedAt(OffsetDateTime.now());
        listingRepository.save(listing);
        return outcome;
    }

    /** 无官方编号确认时的降级：来源自标官方核验 → platform_certified，否则 unverified。 */
    private static String platformFallback(Map<String, Object> raw) {
        return Boolean.TRUE.equals(raw.get("gov_certified")) ? "platform_certified" : "unverified";
    }

    /** 从 raw 里找核验编号（多来源字段名兼容）。 */
    private static String extractCheckNum(Map<String, Object> raw) {
        for (var key : List.of("verify_code", "verifyCode", "checknum", "check_num",
                "核验编号", "核验码", "gov_check_code", "house_verify_code")) {
            var value = ListingNormalizer.cleanText(raw.get(key), 60);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /** 小区一致性：任一方包含另一方（去空白），容忍别名差异。 */
    private static boolean communityMatches(String ours, String official) {
        var a = ours == null ? "" : ours.replaceAll("\\s+", "");
        var b = official == null ? "" : official.replaceAll("\\s+", "");
        if (a.isEmpty() || b.isEmpty()) {
            return true; // 一方缺失时不因此判定不符
        }
        return a.contains(b) || b.contains(a);
    }
}

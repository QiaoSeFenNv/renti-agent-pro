package com.renti.agent.modules.listing.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentUser;
import com.renti.agent.modules.auth.application.UserPrincipal;
import com.renti.agent.modules.listing.application.ListingAdminService;
import com.renti.agent.modules.listing.application.PropertyAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端房源详情（需登录，路径由 SessionAuthInterceptor 保护）。
 */
@Slf4j
@RestController
@RequestMapping("/api/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingAdminService listingAdminService;
    private final PropertyAnalysisService propertyAnalysisService;

    /** 房源详情：{ok, listing, detail(PropertyDetail)}，对齐旧 published_listing_detail_payload */
    @GetMapping("/{listingId}")
    public Map<String, Object> detail(@PathVariable String listingId, @CurrentUser UserPrincipal user) {
        return listingAdminService.publishedDetail(listingId);
    }

    /** 房源深度分析：高德补全通勤/周边并缓存（对齐旧 property_analysis.py） */
    @PostMapping("/{listingId}/detail-analysis")
    public Map<String, Object> detailAnalysis(@PathVariable String listingId,
                                              @CurrentUser UserPrincipal user,
                                              @RequestBody(required = false) Map<String, Object> payload) {
        log.info("listing detail-analysis userId={} listingId={}", user.id(), listingId);
        return propertyAnalysisService.analyze(listingId, payload == null ? Map.of() : payload);
    }
}

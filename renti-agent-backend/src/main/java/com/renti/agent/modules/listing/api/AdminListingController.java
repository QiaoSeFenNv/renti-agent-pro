package com.renti.agent.modules.listing.api;

import java.util.Map;

import com.renti.agent.modules.listing.application.ListingAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端已发布房源：列表/详情/编辑/下架删除（需管理员登录）。
 *
 * <p>/api/admin/listing-ingestion/listings 与 /api/admin/listings 同一实现。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminListingController {

    private final ListingAdminService listingAdminService;

    @GetMapping({"/api/admin/listings", "/api/admin/listing-ingestion/listings"})
    public Map<String, Object> list(@RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(defaultValue = "active") String status,
                                    @RequestParam(defaultValue = "") String query,
                                    @RequestParam(defaultValue = "") String city) {
        return listingAdminService.listPublished(limit, page, status, query, city);
    }

    @GetMapping("/api/admin/listings/{listingId}")
    public Map<String, Object> detail(@PathVariable String listingId) {
        return listingAdminService.getPublished(listingId);
    }

    @PutMapping("/api/admin/listings/{listingId}")
    public Map<String, Object> update(@PathVariable String listingId,
                                      @RequestBody(required = false) Map<String, Object> payload) {
        return listingAdminService.updatePublished(listingId, payload);
    }

    @DeleteMapping("/api/admin/listings/{listingId}")
    public Map<String, Object> delete(@PathVariable String listingId) {
        return listingAdminService.deletePublished(listingId);
    }
}

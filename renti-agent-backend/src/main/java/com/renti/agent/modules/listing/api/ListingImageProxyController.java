package com.renti.agent.modules.listing.api;

import com.renti.agent.modules.listing.application.ImageProxyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 房源图片代理端点（公开）：GET /api/assets/listing-image?url=…
 * 成功返回图片字节并带 24h 公共缓存头；非法地址 400、上游失败 502。
 */
@RestController
@RequiredArgsConstructor
public class ListingImageProxyController {

    private final ImageProxyService imageProxyService;

    @GetMapping("/api/assets/listing-image")
    public ResponseEntity<byte[]> listingImage(@RequestParam(defaultValue = "") String url) {
        var image = imageProxyService.fetch(url);
        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=86400")
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.payload());
    }
}

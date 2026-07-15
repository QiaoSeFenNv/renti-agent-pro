package com.renti.agent.modules.ingestion.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.renti.agent.modules.ingestion.domain.ListingNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 上海房源坐标补全：小区地址逐级降级 → 高德经纬度（对齐旧 _enrich_with_geocode）。
 * 供公开列表爬虫插件复用（Node 抓取的贝壳/链家、Jsoup 抓取的安居客）。
 *
 * <p>作用于 snake_case item：读 district/business_area/community，写 longitude/latitude/geocode_label/
 * geocode_query，district 缺失时用高德返回补齐；全部失败写 geocode_error。高德不可用时静默跳过。</p>
 */
@Component
@RequiredArgsConstructor
public class ShanghaiGeocodeEnricher {

    private final AmapGeocoder amapGeocoder;

    /** 批量补全（就地修改 items）。 */
    public void enrichAll(List<Map<String, Object>> items, String city) {
        if (!amapGeocoder.available()) {
            return;
        }
        for (var item : items) {
            enrich(item, city);
        }
    }

    /** 单条补全：已有坐标或高德不可用则跳过。 */
    public void enrich(Map<String, Object> item, String city) {
        if (!ListingNormalizer.isEmpty(item.get("longitude")) && !ListingNormalizer.isEmpty(item.get("latitude"))) {
            return;
        }
        if (!amapGeocoder.available()) {
            return;
        }
        var addresses = geocodeAddresses(item, city);
        if (addresses.isEmpty()) {
            return;
        }
        var errors = new ArrayList<String>();
        for (var address : addresses) {
            try {
                var result = amapGeocoder.geocode(address, city);
                if (result.isPresent()) {
                    var geocode = result.get();
                    item.put("longitude", geocode.longitude());
                    item.put("latitude", geocode.latitude());
                    item.put("geocode_label", geocode.label());
                    item.put("geocode_query", address);
                    if (!geocode.district().isEmpty()
                            && ListingNormalizer.cleanText(item.get("district"), 40).isEmpty()) {
                        item.put("district", geocode.district());
                    }
                    return;
                }
            } catch (Exception exception) {
                errors.add(String.valueOf(exception.getMessage()));
            }
        }
        var joined = String.join("；", errors.stream().filter(error -> !error.isEmpty()).toList());
        item.put("geocode_error", joined.length() > 240 ? joined.substring(0, 240) : joined);
    }

    private List<String> geocodeAddresses(Map<String, Object> item, String city) {
        var district = ListingNormalizer.cleanText(item.get("district"), 40);
        var businessArea = ListingNormalizer.cleanText(item.get("business_area"), 80);
        var community = ListingNormalizer.cleanText(item.get("community"), 120);
        if (community.isEmpty()) {
            return List.of();
        }
        var prefix = city.isEmpty() ? "" : (city.endsWith("市") ? city : city + "市");
        var candidates = List.of(
                prefix + district + businessArea + community,
                prefix + district + community,
                prefix + businessArea + community,
                prefix + community);
        var result = new ArrayList<String>();
        for (var address : candidates) {
            var cleaned = address.replaceAll("\\s+", "");
            if (cleaned.length() > 4 && !result.contains(cleaned)) {
                result.add(cleaned);
            }
        }
        return result;
    }
}

package com.renti.agent.modules.listing.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.modules.ingestion.domain.ListingNormalizer;
import org.springframework.stereotype.Component;

/**
 * 房源 payload 映射：实体与规范化 payload（camelCase）互转、
 * 管理端 published 响应结构与用户端 PropertyDetail 构建。
 *
 * <p>结构对齐旧 listing_ingestion.py 的 _published_from_row /
 * _public_property_detail_from_listing（字段转 camelCase）。</p>
 */
@Component
public class ListingPayloadMapper {

    /** 实体 → 规范化房源 payload（旧 listing_json 结构，camelCase） */
    public Map<String, Object> listingPayload(ListingEntity entity) {
        var listing = new LinkedHashMap<String, Object>();
        listing.put("id", entity.getListingId());
        listing.put("city", orEmpty(entity.getCity()));
        listing.put("district", orEmpty(entity.getDistrict()));
        listing.put("businessArea", orEmpty(entity.getBusinessArea()));
        listing.put("community", orEmpty(entity.getCommunity()));
        listing.put("title", orEmpty(entity.getTitle()));
        listing.put("longitude", entity.getLongitude());
        listing.put("latitude", entity.getLatitude());
        listing.put("rentPrice", entity.getRentPrice());
        listing.put("layout", orEmpty(entity.getLayout()));
        listing.put("areaSqm", entity.getAreaSqm() == null ? 0 : entity.getAreaSqm());
        listing.put("rentType", orEmpty(entity.getRentType()));
        listing.put("nearestMetro", entity.getNearestMetro() == null || entity.getNearestMetro().isEmpty()
                ? "待补充" : entity.getNearestMetro());
        listing.put("metroDistanceM", entity.getMetroDistanceM() == null ? 999 : entity.getMetroDistanceM());
        listing.put("commuteMinutes", entity.getCommuteMinutes() == null ? 45 : entity.getCommuteMinutes());
        listing.put("tags", entity.getTags() == null ? List.of() : entity.getTags());
        listing.put("riskTags", entity.getRiskTags() == null ? List.of() : entity.getRiskTags());
        listing.put("source", orEmpty(entity.getSource()));
        listing.put("updatedAt", entity.getUpdatedAt() == null
                ? ListingNormalizer.today() : entity.getUpdatedAt().toLocalDate().toString());
        listing.put("sourceUrl", orEmpty(entity.getSourceUrl()));
        listing.put("provider", orEmpty(entity.getProvider()));
        listing.put("externalId", orEmpty(entity.getExternalId()));
        listing.put("image", orEmpty(entity.getImage()));
        listing.put("images", entity.getImages() == null ? List.of() : entity.getImages());
        listing.put("raw", entity.getRaw() == null ? Map.of() : entity.getRaw());
        return listing;
    }

    /** 管理端/详情外层结构（旧 _published_from_row） */
    public Map<String, Object> publishedPayload(ListingEntity entity) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("listingId", entity.getListingId());
        payload.put("candidateId", entity.getCandidateId());
        payload.put("city", orEmpty(entity.getCity()));
        payload.put("listing", listingPayload(entity));
        payload.put("provider", orEmpty(entity.getProvider()));
        payload.put("sourceName", orEmpty(entity.getSourceName()));
        payload.put("sourceUrl", orEmpty(entity.getSourceUrl()));
        payload.put("status", entity.getStatus() == null ? "active" : entity.getStatus());
        payload.put("publishedAt", entity.getPublishedAt() == null ? "" : entity.getPublishedAt().toString());
        payload.put("updatedAt", entity.getUpdatedAt() == null ? "" : entity.getUpdatedAt().toString());
        payload.put("lastSeenAt", "");
        payload.put("unavailableAt", "");
        payload.put("unavailableReason", orEmpty(entity.getUnavailableReason()));
        return payload;
    }

    /** payload → 实体字段（发布/编辑/种子导入共用） */
    public void applyPayload(ListingEntity entity, Map<String, Object> listing) {
        entity.setListingId(ListingNormalizer.cleanText(listing.get("id"), 64));
        entity.setCity(ListingNormalizer.cleanText(listing.get("city"), 32));
        entity.setDistrict(ListingNormalizer.cleanText(listing.get("district"), 64));
        entity.setBusinessArea(ListingNormalizer.cleanText(listing.get("businessArea"), 64));
        entity.setCommunity(ListingNormalizer.cleanText(listing.get("community"), 128));
        entity.setTitle(ListingNormalizer.cleanText(listing.get("title"), 255));
        var longitude = ListingNormalizer.floatFrom(listing.get("longitude"));
        var latitude = ListingNormalizer.floatFrom(listing.get("latitude"));
        entity.setLongitude(longitude == null ? 0.0 : longitude);
        entity.setLatitude(latitude == null ? 0.0 : latitude);
        entity.setRentPrice(ListingNormalizer.intFrom(listing.get("rentPrice")));
        entity.setLayout(ListingNormalizer.cleanText(listing.get("layout"), 32));
        entity.setAreaSqm(ListingNormalizer.intFrom(listing.get("areaSqm")));
        entity.setRentType(ListingNormalizer.cleanText(listing.get("rentType"), 16));
        var nearestMetro = ListingNormalizer.cleanText(listing.get("nearestMetro"), 64);
        entity.setNearestMetro(nearestMetro.isEmpty() ? "待补充" : nearestMetro);
        int metroDistance = ListingNormalizer.intFrom(listing.get("metroDistanceM"));
        entity.setMetroDistanceM(metroDistance == 0 ? 999 : metroDistance);
        int commuteMinutes = ListingNormalizer.intFrom(listing.get("commuteMinutes"));
        entity.setCommuteMinutes(commuteMinutes == 0 ? 45 : commuteMinutes);
        entity.setTags(ListingNormalizer.listFrom(listing.get("tags")));
        entity.setRiskTags(ListingNormalizer.listFrom(listing.get("riskTags")));
        entity.setSource(ListingNormalizer.cleanText(listing.get("source"), 64));
        entity.setSourceUrl(ListingNormalizer.cleanText(listing.get("sourceUrl"), 2000));
        entity.setProvider(ListingNormalizer.cleanText(listing.get("provider"), 64));
        entity.setExternalId(ListingNormalizer.cleanText(listing.get("externalId"), 128));
        var images = imageList(listing.get("images"));
        var image = ListingNormalizer.cleanText(listing.get("image"), 2000);
        if (image.isEmpty() && !images.isEmpty()) {
            image = images.get(0);
        }
        if (!image.isEmpty() && !images.contains(image)) {
            images.add(0, image);
        }
        entity.setImage(image);
        entity.setImages(images);
        entity.setRaw(rawMap(listing.get("raw")));
    }

    /** 用户端 PropertyDetail 大结构（旧 _public_property_detail_from_listing） */
    public Map<String, Object> propertyDetail(ListingEntity entity) {
        var listing = listingPayload(entity);
        var images = new ArrayList<String>(imageList(listing.get("images")));
        var image = ListingNormalizer.cleanText(listing.get("image"), 2000);
        if (image.isEmpty() && !images.isEmpty()) {
            image = images.get(0);
        }
        if (!image.isEmpty() && !images.contains(image)) {
            images.add(0, image);
        }
        var listingId = entity.getListingId();
        double longitude = entity.getLongitude() == 0.0 ? 121.4737 : entity.getLongitude();
        double latitude = entity.getLatitude() == 0.0 ? 31.2304 : entity.getLatitude();
        var community = orDefault(entity.getCommunity(), "房源位置");
        var sourceUrl = orEmpty(entity.getSourceUrl());
        var mapUrl = "https://www.openstreetmap.org/?mlat=" + latitude + "&mlon=" + longitude
                + "#map=17/" + latitude + "/" + longitude;

        var links = new ArrayList<Map<String, Object>>();
        if (!sourceUrl.isEmpty()) {
            links.add(Map.of("label", "打开房源来源", "url", sourceUrl, "kind", "listing"));
        }
        links.add(Map.of("label", "在 OpenStreetMap 查看附近", "url", mapUrl, "kind", "map"));

        int rentPrice = entity.getRentPrice();
        int areaSqm = entity.getAreaSqm() == null ? 0 : entity.getAreaSqm();
        int metroDistance = entity.getMetroDistanceM() == null ? 999 : entity.getMetroDistanceM();
        int commuteMinutes = entity.getCommuteMinutes() == null ? 45 : entity.getCommuteMinutes();
        var nearestMetro = orDefault(entity.getNearestMetro(), "附近地铁");
        var tags = entity.getTags() == null ? List.<String>of() : entity.getTags();
        var riskTags = entity.getRiskTags() == null ? List.<String>of() : entity.getRiskTags();
        var floor = ListingNormalizer.cleanText(
                entity.getRaw() == null ? null : entity.getRaw().get("floor"), 40);

        var address = new ArrayList<String>();
        for (var part : List.of(orEmpty(entity.getDistrict()), orEmpty(entity.getBusinessArea()), community)) {
            if (!part.isEmpty()) {
                address.add(part);
            }
        }

        var detail = new LinkedHashMap<String, Object>();
        detail.put("title", orDefault(entity.getTitle(), community.isEmpty() ? listingId : community));
        detail.put("address", String.join(" · ", address));
        detail.put("image", image);
        detail.put("images", images);
        detail.put("price", rentPrice > 0 ? "¥" + String.format("%,d", rentPrice) : "价格待确认");
        detail.put("availability", "已发布，需核验");
        detail.put("layout", orDefault(entity.getLayout(), "户型待补充"));
        detail.put("baths", "1卫");
        detail.put("size", areaSqm > 0 ? areaSqm + "㎡" : "面积待补充");
        detail.put("floor", floor.isEmpty() ? "楼层待补充" : floor);
        detail.put("score", 70);
        detail.put("insight", "该采集房源位于 " + community + "，距 " + nearestMetro + " 约 " + metroDistance + " 米。");
        detail.put("pros", tags.isEmpty() ? List.of("正式房源库已发布") : tags.subList(0, Math.min(3, tags.size())));
        detail.put("cons", riskTags.isEmpty()
                ? List.of("价格、图片和可租状态仍需跳转来源核验")
                : riskTags.subList(0, Math.min(3, riskTags.size())));
        detail.put("commute", List.of(
                Map.of("label", nearestMetro, "value", metroDistance + "m", "icon", "subway", "tone", "primary"),
                Map.of("label", "估算通勤", "value", commuteMinutes + "m", "icon", "directions_transit", "tone", "muted")));
        detail.put("commuteMap", Map.of(
                "hasTargetDistance", false,
                "target", Map.of("label", "目标地址", "longitude", longitude, "latitude", latitude),
                "property", Map.of("label", community, "longitude", longitude, "latitude", latitude),
                "distanceMeters", 0,
                "amenities", List.of(
                        Map.of("label", nearestMetro, "type", "metro", "distanceMeters", metroDistance))));

        var dataSource = new LinkedHashMap<String, Object>();
        dataSource.put("provider", firstNonEmpty(entity.getProvider(), entity.getSource(), "ingested"));
        dataSource.put("sourceType", "ingested_listing");
        dataSource.put("sourceName", firstNonEmpty(entity.getSourceName(), entity.getSource(), ""));
        dataSource.put("sourceUrl", sourceUrl);
        dataSource.put("recordId", listingId);
        dataSource.put("collectedAt", entity.getPublishedAt() == null ? "" : entity.getPublishedAt().toString());
        dataSource.put("updatedAt", entity.getUpdatedAt() == null ? "" : entity.getUpdatedAt().toString());
        dataSource.put("accessMethod", "由后台采集模块导入并经管理员审核发布。");
        dataSource.put("reliabilityNote", "该记录来自正式房源库，仍建议跳转来源链接核验价格、图片、可租状态和联系方式。");
        dataSource.put("links", links);
        detail.put("dataSource", dataSource);
        return detail;
    }

    private List<String> imageList(Object value) {
        var result = new ArrayList<String>();
        if (value instanceof List<?> list) {
            for (var item : list) {
                var cleaned = ListingNormalizer.cleanText(item, 2000);
                if (!cleaned.isEmpty() && !result.contains(cleaned)) {
                    result.add(cleaned);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rawMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private String firstNonEmpty(String... values) {
        for (var value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }
}

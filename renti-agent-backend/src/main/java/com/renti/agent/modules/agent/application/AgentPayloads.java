package com.renti.agent.modules.agent.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renti.agent.infrastructure.persistence.entity.ListingEntity;

/**
 * agent 模块内的 payload 组装工具：ListingEntity → 契约 camelCase Map 等。
 */
public final class AgentPayloads {

    private AgentPayloads() {
    }

    public static Map<String, Object> listingMap(ListingEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("listingId", entity.getListingId());
        map.put("id", entity.getListingId());
        map.put("city", entity.getCity());
        map.put("district", entity.getDistrict());
        map.put("businessArea", entity.getBusinessArea());
        map.put("community", entity.getCommunity());
        map.put("title", entity.getTitle());
        map.put("longitude", entity.getLongitude());
        map.put("latitude", entity.getLatitude());
        map.put("rentPrice", entity.getRentPrice());
        map.put("layout", entity.getLayout());
        map.put("areaSqm", entity.getAreaSqm());
        map.put("rentType", entity.getRentType());
        map.put("nearestMetro", entity.getNearestMetro());
        map.put("metroDistanceM", entity.getMetroDistanceM());
        map.put("commuteMinutes", entity.getCommuteMinutes());
        map.put("tags", entity.getTags() == null ? List.of() : entity.getTags());
        map.put("riskTags", entity.getRiskTags() == null ? List.of() : entity.getRiskTags());
        map.put("source", entity.getSource());
        map.put("sourceName", entity.getSourceName());
        map.put("sourceUrl", entity.getSourceUrl());
        map.put("provider", entity.getProvider());
        map.put("image", entity.getImage());
        map.put("images", entity.getImages() == null ? List.of() : entity.getImages());
        map.put("publishedAt", entity.getPublishedAt());
        map.put("updatedAt", entity.getUpdatedAt());
        return map;
    }

    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static String text(Object value, String fallback) {
        var cleaned = text(value);
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    public static Integer optionalInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            var cleaned = text(value);
            return cleaned.isEmpty() ? null : (int) Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }
}
